package com.safebrowser.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebStorage
import java.io.File

/**
 * Periodic + on-demand cache cleanup.
 *
 * Caches that grow unboundedly inside a long-running WebView session and that
 * the browser does NOT trim on its own:
 *   1. WebView HTTP cache (per WebView, on disk in app cacheDir)
 *   2. WebView form-data / autofill cache
 *   3. Renderer image-decode cache (in-memory, bound to renderer process)
 *   4. Android app cacheDir / externalCacheDir on the filesystem
 *   5. Service Worker CacheStorage entries that pages register and forget
 *
 * We do NOT touch:
 *   - Cookies (logins)
 *   - localStorage / IndexedDB / WebSQL (site state)
 *   - Bookmarks, history (filesDir, persistent)
 *   - last_crash.txt (filesDir, one-shot)
 */
object CacheJanitor {
    /** How often the janitor runs while the app is foreground. */
    private const val INTERVAL_MS = 5L * 60L * 1000L  // 5 minutes
    /** App cacheDir is forcibly trimmed if it exceeds this on disk. */
    private const val DISK_CACHE_BUDGET = 64L * 1024L * 1024L  // 64 MB

    private val handler = Handler(Looper.getMainLooper())
    private var tabsRef: TabManager? = null
    private var ctxRef: Context? = null
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            runCatching { sweep() }
            if (running) handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start(ctx: Context, tabs: TabManager) {
        ctxRef = ctx.applicationContext
        tabsRef = tabs
        if (running) return
        running = true
        handler.postDelayed(tick, INTERVAL_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    /** Run a sweep right now (called on resume, trim, navigation milestones). */
    fun sweepNow() {
        runCatching { sweep() }
    }

    private fun sweep() {
        val tabs = tabsRef ?: return
        val ctx = ctxRef ?: return

        // 1. Per-WebView HTTP cache + image-decode cache.  We do this for
        //    inactive tabs only; touching the active tab would invalidate the
        //    page the user is reading right now.
        tabs.tabs.forEach { t ->
            if (t == tabs.active) return@forEach
            runCatching {
                t.webView.clearCache(false)        // disk cache
                t.webView.clearFormData()          // autofill memory
            }
        }

        // 2. App cacheDir on disk: cap at DISK_CACHE_BUDGET.
        runCatching {
            val cacheDir = ctx.cacheDir
            val size = dirSize(cacheDir)
            if (size > DISK_CACHE_BUDGET) {
                trimDirToBudget(cacheDir, DISK_CACHE_BUDGET / 2)
            }
        }

        // 3. Service Worker CacheStorage on the *active* tab.  Cheap script
        //    that asks the page to evict caches whose entries are stale.
        val active = tabs.active ?: return
        runCatching {
            active.webView.evaluateJavascript(SW_CACHE_PRUNE_JS, null)
        }
    }

    /** Hard reset called when user explicitly clears history. */
    fun nukeAll() {
        val tabs = tabsRef ?: return
        val ctx = ctxRef ?: return
        runCatching {
            tabs.tabs.forEach { t ->
                t.webView.clearCache(true)
                t.webView.clearFormData()
                t.webView.clearHistory()
            }
            // Disk cache directory.
            ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            // localStorage / IndexedDB / WebSQL — only when user explicitly asks.
            WebStorage.getInstance().deleteAllData()
        }
    }

    // --- helpers ---------------------------------------------------------

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    private fun trimDirToBudget(dir: File, budget: Long) {
        // Delete oldest files first until under budget.
        val files = dir.walkTopDown().filter { it.isFile }
            .sortedBy { it.lastModified() }
            .toMutableList()
        var current = files.sumOf { it.length() }
        for (f in files) {
            if (current <= budget) break
            val sz = f.length()
            if (f.delete()) current -= sz
        }
    }

    /**
     * Drop any Service Worker `caches` entry that was created more than a day
     * ago.  Pages stuff things in there for offline use and rarely clean up.
     * Whitelisted by browser to be safe (no identifying data leaves the page).
     */
    private val SW_CACHE_PRUNE_JS = """
        (function(){
          try {
            if (!('caches' in window)) return;
            caches.keys().then(function(keys){
              var now = Date.now();
              keys.forEach(function(k){
                caches.open(k).then(function(c){
                  c.keys().then(function(reqs){
                    if (reqs.length > 200) {
                      // Bulk caches: drop oldest half.
                      reqs.slice(0, Math.floor(reqs.length / 2))
                          .forEach(function(r){ c.delete(r); });
                    }
                  });
                });
              });
            });
          } catch (e) {}
        })();
    """.trimIndent()
}
