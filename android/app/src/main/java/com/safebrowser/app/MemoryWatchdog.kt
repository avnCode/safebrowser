package com.safebrowser.app

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Pre-emptive memory pressure monitor.
 *
 * Android's `onTrimMemory(LOW)` callback often fires *after* the OS has
 * already decided to kill a renderer.  We poll system + app memory every
 * 20 s and act *before* that happens:
 *
 *   - APP heap > 75 % of max  -> hibernate inactive tabs + free image cache
 *   - System availMem < 12 %  -> hibernate inactive tabs
 *   - System lowMemory == true -> hibernate ALL inactive + nuke disk cache
 *
 * Also auto-hibernates any inactive tab that has not been the active tab
 * for 5 minutes.
 */
object MemoryWatchdog {
    private const val POLL_MS              = 20_000L
    private const val IDLE_HIBERNATE_MS    = 5L * 60_000L   // 5 minutes
    private const val APP_HEAP_HIGH        = 0.75
    private const val SYS_AVAIL_LOW_RATIO  = 0.12

    private val handler = Handler(Looper.getMainLooper())
    private var ctxRef: Context? = null
    private var tabsRef: TabManager? = null
    private var running = false
    private val lastActive = mutableMapOf<Long, Long>()  // tabId -> uptimeMs

    private val tick = object : Runnable {
        override fun run() {
            runCatching { check() }
            if (running) handler.postDelayed(this, POLL_MS)
        }
    }

    fun start(ctx: Context, tabs: TabManager) {
        ctxRef = ctx.applicationContext
        tabsRef = tabs
        if (running) return
        running = true
        handler.postDelayed(tick, POLL_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        lastActive.clear()
    }

    /** Called by TabManager whenever a tab becomes active. */
    fun touchActive(tabId: Long) {
        lastActive[tabId] = SystemClock.uptimeMillis()
    }

    private fun check() {
        val ctx = ctxRef ?: return
        val tabs = tabsRef ?: return

        // 1. App heap pressure.
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        val maxHeap = rt.maxMemory()
        val heapRatio = used.toDouble() / maxHeap.toDouble()

        // 2. System memory pressure.
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val sysRatio = mi.availMem.toDouble() / mi.totalMem.toDouble()

        when {
            mi.lowMemory -> {
                // OS says low memory NOW.  Drop everything we safely can.
                tabs.hibernateAllInactive()
                CacheJanitor.sweepNow()
                runCatching { tabs.active?.webView?.freeMemory() }
            }
            heapRatio >= APP_HEAP_HIGH || sysRatio <= SYS_AVAIL_LOW_RATIO -> {
                tabs.hibernateAllInactive()
                CacheJanitor.sweepNow()
            }
            else -> {
                // 3. Idle-tab auto-hibernation.
                val now = SystemClock.uptimeMillis()
                for (t in tabs.tabs.toList()) {
                    if (t == tabs.active) {
                        lastActive[t.id] = now
                        continue
                    }
                    val seen = lastActive[t.id] ?: now.also { lastActive[t.id] = now }
                    if (now - seen >= IDLE_HIBERNATE_MS) tabs.hibernate(t)
                }
            }
        }
    }
}
