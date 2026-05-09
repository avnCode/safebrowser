package com.safebrowser.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : AppCompatActivity(), TabManager.Callbacks {

    private lateinit var addr: EditText
    private lateinit var btnBack: ImageButton
    private lateinit var btnFwd: ImageButton
    private lateinit var btnReload: ImageButton
    private lateinit var btnNewTab: ImageButton
    private lateinit var btnShield: ImageButton
    private lateinit var btnBookmark: ImageButton
    private lateinit var btnMore: ImageButton
    private lateinit var btnClearUrl: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var webContainer: FrameLayout
    private lateinit var tabStrip: LinearLayout

    private lateinit var settings: Settings
    private lateinit var adBlocker: AdBlocker
    private lateinit var bookmarks: Bookmarks
    private lateinit var history: History
    private lateinit var tabs: TabManager

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashLogger()
        super.onCreate(savedInstanceState)
        // Pre-warm the WebView renderer process so the first navigation
        // doesn't pay cold-start cost (renderer fork + V8 init + GPU warm-up
        // is what makes the first video page so fragile).  We destroy the
        // throwaway WebView immediately so it doesn't hold host-process
        // memory — the renderer process itself is already warmed up and
        // stays alive (it's a process-singleton).
        runCatching {
            val warm = android.webkit.WebView(applicationContext)
            warm.destroy()
        }
        setContentView(R.layout.activity_main)

        readAndClearLastCrash()?.let { showCrashDialog(it) }

        settings    = Settings(this)
        adBlocker   = AdBlocker(this, settings)
        bookmarks   = Bookmarks(this)
        history     = History(this)
        addr        = findViewById(R.id.address)
        btnBack     = findViewById(R.id.btn_back)
        btnFwd      = findViewById(R.id.btn_forward)
        btnReload   = findViewById(R.id.btn_reload)
        btnNewTab   = findViewById(R.id.btn_new_tab)
        btnShield   = findViewById(R.id.btn_shield)
        btnBookmark = findViewById(R.id.btn_bookmark)
        btnMore     = findViewById(R.id.btn_more)
        btnClearUrl = findViewById(R.id.btn_clear_url)
        progress    = findViewById(R.id.progress)
        webContainer= findViewById(R.id.web_container)
        tabStrip    = findViewById(R.id.tab_strip)

        tabs = TabManager(this, webContainer, tabStrip, this, adBlocker, settings)
        CacheJanitor.start(this, tabs)
        MemoryWatchdog.start(this, tabs)

        btnBack.setOnClickListener   { tabs.active?.let { if (it.webView.canGoBack()) tabs.goBackSafely(it) } }
        btnFwd.setOnClickListener    { tabs.active?.webView?.takeIf { it.canGoForward() }?.goForward() }
        btnReload.setOnClickListener { tabs.active?.webView?.reload() }
        btnNewTab.setOnClickListener { tabs.newTab() }
        btnShield.setOnClickListener { showShieldDialog() }
        btnBookmark.setOnClickListener { toggleBookmark() }
        btnMore.setOnClickListener   { showOverflowMenu(it) }
        btnClearUrl.setOnClickListener { addr.text.clear(); addr.requestFocus() }

        // Show/hide the clear (X) button as the user types.
        addr.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                btnClearUrl.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
        })

        addr.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                       actionId == EditorInfo.IME_ACTION_DONE ||
                       (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            if (isGo) { submitAddress(); true } else false
        }

        // Tap address bar selects all → easier to type a new URL.
        addr.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) addr.selectAll() }

        adBlocker.enabled = settings.adBlockEnabled
        tabs.newTab(NEW_TAB_URL, activate = true)

        // Predictive-back / hardware back: walk WebView history first.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreenView != null) { onHideFullscreen(); return }
                val tab = tabs.active
                if (tab != null && tab.webView.canGoBack()) { tabs.goBackSafely(tab); return }
                tab?.let {
                    if (tabs.tabs.size > 1) { tabs.close(it); return }
                }
                // Last tab, no history → let the system minimise.
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })

        // If we were launched with a URL from another app, open it.
        intent?.dataString?.takeIf { it.isNotBlank() }?.let { tabs.newTab(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.takeIf { it.isNotBlank() }?.let { tabs.newTab(it) }
    }

    override fun onPause() {
        super.onPause()
        if (settings.backgroundPlaybackEnabled) {
            // Start foreground service to keep the process alive and prevent
            // Samsung One UI from killing us in the background.
            PlaybackService.start(this)
            // Inject visibility override into the active tab so the video
            // player doesn't voluntarily pause on visibilitychange.
            tabs.active?.webView?.let { wv ->
                runCatching {
                    val js = assets.open("visibility_override.js").bufferedReader().use { it.readText() }
                    wv.evaluateJavascript(js, null)
                }
            }
            return  // keep playing in background
        }
        tabs.pauseActive()
    }

    override fun onResume() {
        super.onResume()
        // Always stop the foreground service when we're back in the foreground.
        PlaybackService.stop(this)
        tabs.resumeActive()
        CacheJanitor.sweepNow()
    }

    override fun onDestroy() {
        MemoryWatchdog.stop()
        CacheJanitor.stop()
        PlaybackService.stop(this)
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            tabs.trimInactive(level)
            CacheJanitor.sweepNow()
        }
    }

    private fun submitAddress() {
        val url = UrlNormalizer.normalize(addr.text.toString())
        if (url.isBlank()) return
        val tab = tabs.active ?: tabs.newTab(url) ?: return
        // Detect cross-origin navigation BEFORE mutating tab.url. Bug #15:
        // drop bfcache so the previous page's full DOM/JS heap doesn't sit
        // in the shared renderer alongside the new page being parsed.
        val curOrigin = UrlNormalizer.origin(tab.url)
        val nxtOrigin = UrlNormalizer.origin(url)
        if (curOrigin != nxtOrigin && !nxtOrigin.isNullOrEmpty()) {
            tabs.resetForNavigation(tab, url)
        }
        tab.expectedOrigin = UrlNormalizer.origin(url) ?: tab.expectedOrigin
        tab.url = url
        runCatching {
            tab.webView.stopLoading()
            // Free the previous page's image/JS caches BEFORE the new page
            // starts loading. Without this the renderer briefly holds both.
            tab.webView.freeMemory()
        }
        tab.webView.loadUrl(url)
        // hide keyboard
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(addr.windowToken, 0)
        addr.clearFocus()
    }

    private fun refreshModeButton() {
        // No-op: mode is shown in the overflow menu now.
    }

    private fun showShieldDialog() {
        val sessionBlocked = adBlocker.blockedThisSession
        val items = arrayOf(
            if (settings.adBlockEnabled) "Disable ad blocker" else "Enable ad blocker",
            "Manage allowed sites (${settings.allowedOrigins().size})",
            "Manage blocked sites (${settings.blockedOrigins().size})",
        )
        AlertDialog.Builder(this)
            .setTitle("SafeBrowser")
            .setMessage("Ad blocker: ${if (settings.adBlockEnabled) "ON" else "OFF"}\n" +
                        "Blocked this session: $sessionBlocked\n" +
                        "Mode: ${settings.mode.name}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        settings.adBlockEnabled = !settings.adBlockEnabled
                        adBlocker.enabled = settings.adBlockEnabled
                        Toast.makeText(this,
                            "Ad blocker ${if (settings.adBlockEnabled) "enabled" else "disabled"}",
                            Toast.LENGTH_SHORT).show()
                    }
                    1 -> showAllowList()
                    2 -> showBlockList()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showAllowList() {
        val list = settings.allowedOrigins().toList().sorted()
        if (list.isEmpty()) { Toast.makeText(this, "No allowed sites", Toast.LENGTH_SHORT).show(); return }
        val arr = list.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Allowed sites \u2014 tap to remove")
            .setItems(arr) { _, idx ->
                val keep = list.filterIndexed { i, _ -> i != idx }.toSet()
                getSharedPreferences("safebrowser", MODE_PRIVATE)
                    .edit().putStringSet("allowed_origins", keep).apply()
                Toast.makeText(this, "Removed ${arr[idx]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showBlockList() {
        val list = settings.blockedOrigins().toList().sorted()
        if (list.isEmpty()) { Toast.makeText(this, "No blocked sites", Toast.LENGTH_SHORT).show(); return }
        val arr = list.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Blocked sites \u2014 tap to unblock")
            .setItems(arr) { _, idx ->
                settings.unblockOrigin(arr[idx])
                Toast.makeText(this, "Unblocked ${arr[idx]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---- Bookmarks / history ------------------------------------------

    private fun refreshBookmarkIcon() {
        val url = tabs.active?.url ?: ""
        btnBookmark.setImageResource(
            if (bookmarks.isBookmarked(url)) R.drawable.ic_star else R.drawable.ic_star_outline
        )
    }

    private fun toggleBookmark() {
        val tab = tabs.active ?: return
        val url = tab.url
        if (url.isBlank() || url.startsWith("file://")) {
            Toast.makeText(this, "Nothing to bookmark", Toast.LENGTH_SHORT).show()
            return
        }
        if (bookmarks.isBookmarked(url)) {
            bookmarks.remove(url)
            Toast.makeText(this, "Removed bookmark", Toast.LENGTH_SHORT).show()
        } else {
            bookmarks.add(url, tab.pageTitle)
            Toast.makeText(this, "Bookmarked", Toast.LENGTH_SHORT).show()
        }
        refreshBookmarkIcon()
    }

    private fun showBookmarks() {
        val list = bookmarks.list()
        if (list.isEmpty()) { Toast.makeText(this, "No bookmarks", Toast.LENGTH_SHORT).show(); return }
        val labels = list.map { "${it.title}\n${it.url}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Bookmarks")
            .setItems(labels) { _, idx ->
                val item = list[idx]
                val tab = tabs.active ?: tabs.newTab(item.url) ?: return@setItems
                val curOrigin = UrlNormalizer.origin(tab.url)
                val nxtOrigin = UrlNormalizer.origin(item.url)
                if (curOrigin != nxtOrigin && !nxtOrigin.isNullOrEmpty()) {
                    tabs.resetForNavigation(tab, item.url)
                }
                tab.expectedOrigin = UrlNormalizer.origin(item.url) ?: tab.expectedOrigin
                tab.webView.loadUrl(item.url)
            }
            .setNeutralButton("Clear all") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Clear all bookmarks?")
                    .setPositiveButton("Clear") { _, _ ->
                        bookmarks.clear()
                        refreshBookmarkIcon()
                        Toast.makeText(this, "Bookmarks cleared", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showHistory() {
        val list = history.list()
        if (list.isEmpty()) { Toast.makeText(this, "No history", Toast.LENGTH_SHORT).show(); return }
        val labels = list.map { "${it.title}\n${it.url}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("History")
            .setItems(labels) { _, idx ->
                val item = list[idx]
                val tab = tabs.active ?: tabs.newTab(item.url) ?: return@setItems
                val curOrigin = UrlNormalizer.origin(tab.url)
                val nxtOrigin = UrlNormalizer.origin(item.url)
                if (curOrigin != nxtOrigin && !nxtOrigin.isNullOrEmpty()) {
                    tabs.resetForNavigation(tab, item.url)
                }
                tab.expectedOrigin = UrlNormalizer.origin(item.url) ?: tab.expectedOrigin
                tab.webView.loadUrl(item.url)
            }
            .setNeutralButton("Clear history") { _, _ -> confirmClearHistory() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle("Clear all history?")
            .setPositiveButton("Clear") { _, _ ->
                history.clear()
                CacheJanitor.nukeAll()
                Toast.makeText(this, "History and caches cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleBackgroundPlayback() {
        if (settings.backgroundPlaybackEnabled) {
            settings.backgroundPlaybackEnabled = false
            PlaybackService.stop(this)
            Toast.makeText(this, "Background playback OFF", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Allow background playback?")
            .setMessage("When enabled, audio/video keeps playing after you switch away from " +
                        "SafeBrowser. A notification will appear while playback is active.\n\n" +
                        "Disable when finished to avoid silent battery drain.")
            .setPositiveButton("Enable") { _, _ ->
                settings.backgroundPlaybackEnabled = true
                Toast.makeText(this, "Background playback ON", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showOverflowMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        val isStrict = settings.mode == Mode.Strict
        menu.menu.add(0, 1, 0, if (isStrict) "Mode: Strict (tap for Lenient)" else "Mode: Lenient (tap for Strict)")
        menu.menu.add(0, 2, 1, if (settings.adBlockEnabled) "Ad blocker: ON" else "Ad blocker: OFF")
        menu.menu.add(0, 13, 2, if (settings.overlayBlockerEnabled) "Overlay blocker: ON" else "Overlay blocker: OFF")
        menu.menu.add(0, 14, 3, if (settings.backgroundPlaybackEnabled) "Background playback: ON" else "Background playback: OFF")
        menu.menu.add(0, 8, 4, if (settings.historyEnabled) "History: ON" else "History: OFF")
        menu.menu.add(0, 9, 5, "Bookmarks")
        menu.menu.add(0, 10, 6, "History")
        menu.menu.add(0, 11, 7, "Clear history")
        menu.menu.add(0, 15, 8, "Allowed sites (${settings.allowedOrigins().size})")
        menu.menu.add(0, 16, 9, "Blocked sites (${settings.blockedOrigins().size})")
        menu.menu.add(0, 12, 10, "Open Downloads")
        menu.menu.add(0, 3, 11, "New tab")
        menu.menu.add(0, 4, 12, "Close current tab")
        menu.menu.add(0, 5, 13, "Reload")
        menu.menu.add(0, 17, 14, "Hide overlays now")
        menu.menu.add(0, 6, 15, "Copy URL")
        menu.menu.add(0, 7, 16, "Share")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    settings.mode = if (isStrict) Mode.Lenient else Mode.Strict
                    Toast.makeText(this, "Mode: ${settings.mode.name}", Toast.LENGTH_SHORT).show()
                }
                2 -> {
                    settings.adBlockEnabled = !settings.adBlockEnabled
                    adBlocker.enabled = settings.adBlockEnabled
                    Toast.makeText(this,
                        "Ad blocker ${if (settings.adBlockEnabled) "ON" else "OFF"}",
                        Toast.LENGTH_SHORT).show()
                }
                3 -> tabs.newTab()
                4 -> tabs.active?.let { tabs.close(it) }
                5 -> tabs.active?.webView?.reload()
                6 -> {
                    val url = tabs.active?.url ?: ""
                    if (url.isNotBlank()) {
                        val cm = getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("url", url))
                        Toast.makeText(this, "URL copied", Toast.LENGTH_SHORT).show()
                    }
                }
                7 -> {
                    val url = tabs.active?.url ?: return@setOnMenuItemClickListener true
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    startActivity(Intent.createChooser(send, "Share"))
                }
                8 -> {
                    settings.historyEnabled = !settings.historyEnabled
                    Toast.makeText(this,
                        "History ${if (settings.historyEnabled) "ON" else "OFF"}",
                        Toast.LENGTH_SHORT).show()
                }
                9 -> showBookmarks()
                10 -> showHistory()
                11 -> confirmClearHistory()
                12 -> {
                    runCatching {
                        startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure {
                        Toast.makeText(this, "Could not open Downloads", Toast.LENGTH_SHORT).show()
                    }
                }
                13 -> {
                    settings.overlayBlockerEnabled = !settings.overlayBlockerEnabled
                    Toast.makeText(this,
                        "Overlay blocker ${if (settings.overlayBlockerEnabled) "ON" else "OFF"}",
                        Toast.LENGTH_SHORT).show()
                }
                14 -> toggleBackgroundPlayback()
                15 -> showAllowList()
                16 -> showBlockList()
                17 -> {
                    val js = runCatching {
                        assets.open("overlay_zapper.js").bufferedReader().use { it.readText() }
                    }.getOrNull()
                    if (!js.isNullOrBlank()) {
                        tabs.active?.webView?.evaluateJavascript(js, null)
                        Toast.makeText(this, "Overlays hidden", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            true
        }
        menu.show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return super.onKeyDown(keyCode, event)
    }

    // ---- TabManager.Callbacks ------------------------------------------

    override fun onActiveChanged(tab: Tab?) {
        if (tab == null) return
        addr.setText(if (tab.url.startsWith("file://")) "" else tab.url)
        progress.visibility = View.GONE
        refreshBookmarkIcon()
    }

    override fun onProgress(tab: Tab, newProgress: Int) {
        if (tab != tabs.active) return
        if (newProgress in 1..99) {
            progress.visibility = View.VISIBLE
            progress.progress = newProgress
        } else {
            progress.visibility = View.GONE
        }
    }

    override fun onUrlChanged(tab: Tab) {
        if (tab == tabs.active && !addr.isFocused) {
            addr.setText(if (tab.url.startsWith("file://")) "" else tab.url)
        }
        if (tab == tabs.active) refreshBookmarkIcon()
        if (settings.historyEnabled) history.add(tab.url, tab.pageTitle)
    }

    override fun shouldAllowNavigation(tab: Tab, nextUrl: String, isUserGesture: Boolean): Boolean {
        if (nextUrl.startsWith("file://") || nextUrl.startsWith("about:") ||
            nextUrl.startsWith("javascript:") || nextUrl.startsWith("data:")) return true

        // Treat non-http(s) (mailto:, tel:, intent:, market:, geo:, etc.) as
        // an external app launch — we don't load them.
        if (!nextUrl.startsWith("http://") && !nextUrl.startsWith("https://")) {
            return tryOpenExternal(nextUrl)
        }

        val expected   = tab.expectedOrigin
        val nextOrigin = UrlNormalizer.origin(nextUrl)

        // Hard block list — silent, no prompt, regardless of mode.
        if (settings.isBlocked(nextOrigin)) {
            Toast.makeText(this, "Blocked: $nextOrigin", Toast.LENGTH_SHORT).show()
            return false
        }

        // Allow-list — silent, no prompt.
        if (settings.isAllowed(nextOrigin)) {
            tab.expectedOrigin = nextOrigin ?: tab.expectedOrigin
            return true
        }

        if (expected == null) {
            tab.expectedOrigin = nextOrigin ?: tab.expectedOrigin
            return true
        }
        if (nextOrigin != null && nextOrigin == expected) return true
        if (UrlNormalizer.sameRegistrableDomain(
                UrlNormalizer.host(expected), UrlNormalizer.host(nextUrl))) {
            tab.expectedOrigin = nextOrigin ?: tab.expectedOrigin
            return true
        }
        if (settings.mode == Mode.Lenient) {
            tab.expectedOrigin = nextOrigin ?: tab.expectedOrigin
            return true
        }
        // Strict + cross-origin → always prompt, even on user-gesture clicks.
        showRedirectDialog(tab, expected, nextUrl, isUserGesture)
        return false
    }

    override fun onPopupBlocked() {
        Toast.makeText(this, "Popup blocked", Toast.LENGTH_SHORT).show()
    }

    override fun onRendererCrashed(tab: Tab, crashed: Boolean) {
        val reason = if (crashed) "Renderer crashed" else "Renderer killed (low memory)"
        val bfSize = tabs.backForwardListSize()
        val heapMb = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        Toast.makeText(
            this,
            "$reason — page reloaded\nbf=$bfSize heap=${heapMb}MB",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onLinkLongPressed(tab: Tab, linkUrl: String, imageUrl: String?) {
        val opts = mutableListOf<Pair<String, () -> Unit>>()
        opts += "Open in new tab" to { tabs.newTab(linkUrl) }
        opts += "Copy link" to {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("link", linkUrl))
            Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
        }
        opts += "Save link" to {
            Downloader.enqueue(this, linkUrl,
                userAgent = tab.webView.settings.userAgentString,
                referer = tab.url)
        }
        if (!imageUrl.isNullOrBlank() && imageUrl != linkUrl) {
            opts += "Save image" to {
                Downloader.enqueue(this, imageUrl,
                    userAgent = tab.webView.settings.userAgentString,
                    referer = tab.url)
            }
        }
        val labels = opts.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(linkUrl)
            .setItems(labels) { _, idx -> opts[idx].second() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDownloadRequested(
        url: String, userAgent: String?, contentDisposition: String?,
        mimeType: String?, contentLength: Long,
    ) {
        val sizeStr = if (contentLength > 0) {
            val mb = contentLength / (1024.0 * 1024.0)
            String.format("%.1f MB", mb)
        } else "unknown size"
        AlertDialog.Builder(this)
            .setTitle("Download?")
            .setMessage("$url\n\n$sizeStr")
            .setPositiveButton("Download") { _, _ ->
                Downloader.enqueue(this, url, userAgent, contentDisposition, mimeType,
                    referer = tabs.active?.url)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- HTML5 fullscreen --------------------------------------------------

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    override fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = callback
        val decor = window.decorView as ViewGroup
        decor.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Hide system bars (immersive).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val ctrl = WindowInsetsControllerCompat(window, window.decorView)
        ctrl.hide(WindowInsetsCompat.Type.systemBars())
        ctrl.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onHideFullscreen() {
        val v = fullscreenView ?: return
        val decor = window.decorView as ViewGroup
        decor.removeView(v)
        fullscreenView = null
        runCatching { fullscreenCallback?.onCustomViewHidden() }
        fullscreenCallback = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val ctrl = WindowInsetsControllerCompat(window, window.decorView)
        ctrl.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun tryOpenExternal(url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            false   // we already handled it; tell WebView not to load
        } catch (_: Exception) {
            Toast.makeText(this, "No app can open: $url", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun showRedirectDialog(tab: Tab, expected: String, nextUrl: String, isUserGesture: Boolean) {
        val nextOrigin = UrlNormalizer.origin(nextUrl) ?: nextUrl
        val reason = if (isUserGesture)
            "You tapped a link that goes to a different site."
        else
            "This page tried to send you to a different site."
        AlertDialog.Builder(this)
            .setTitle("Cross-site navigation")
            .setMessage("From: $expected\n\nTo: $nextUrl\n\n$reason")
            .setPositiveButton("Allow once") { _, _ ->
                // Cross-origin by construction (this dialog only shows on origin change).
                tabs.resetForNavigation(tab, nextUrl)
                tab.expectedOrigin = UrlNormalizer.origin(nextUrl) ?: tab.expectedOrigin
                tab.webView.loadUrl(nextUrl)
            }
            .setNeutralButton("Always allow") { _, _ ->
                settings.allowOrigin(nextOrigin)
                Toast.makeText(this, "Always allowing $nextOrigin", Toast.LENGTH_SHORT).show()
                tabs.resetForNavigation(tab, nextUrl)
                tab.expectedOrigin = UrlNormalizer.origin(nextUrl) ?: tab.expectedOrigin
                tab.webView.loadUrl(nextUrl)
            }
            .setNegativeButton("Block site") { _, _ ->
                settings.blockOrigin(nextOrigin)
                Toast.makeText(this, "Blocked $nextOrigin", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ---- crash logging --------------------------------------------------

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            saveCrashTrace(throwable, thread.name)
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun saveCrashTrace(t: Throwable, threadName: String = Thread.currentThread().name) {
        try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            pw.println("---- SafeBrowser crash ----")
            pw.println("time: " + java.util.Date())
            pw.println("thread: $threadName")
            pw.println("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            pw.println("android: ${android.os.Build.VERSION.RELEASE} (api ${android.os.Build.VERSION.SDK_INT})")
            pw.println()
            t.printStackTrace(pw)
            pw.flush()
            File(filesDir, "last_crash.txt").writeText(sw.toString())
        } catch (_: Throwable) { /* don't loop */ }
    }

    private fun readAndClearLastCrash(): String? {
        val f = File(filesDir, "last_crash.txt")
        if (!f.exists()) return null
        return runCatching { val txt = f.readText(); f.delete(); txt }.getOrNull()
    }

    private fun showCrashDialog(trace: String) {
        AlertDialog.Builder(this)
            .setTitle("Previous crash (tap Copy)")
            .setMessage(trace)
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("crash", trace))
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }
}
