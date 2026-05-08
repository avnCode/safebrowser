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
        progress    = findViewById(R.id.progress)
        webContainer= findViewById(R.id.web_container)
        tabStrip    = findViewById(R.id.tab_strip)

        tabs = TabManager(this, webContainer, tabStrip, this, adBlocker)

        btnBack.setOnClickListener   { tabs.active?.webView?.takeIf { it.canGoBack() }?.goBack() }
        btnFwd.setOnClickListener    { tabs.active?.webView?.takeIf { it.canGoForward() }?.goForward() }
        btnReload.setOnClickListener { tabs.active?.webView?.reload() }
        btnNewTab.setOnClickListener { tabs.newTab() }
        btnShield.setOnClickListener { showShieldDialog() }
        btnBookmark.setOnClickListener { toggleBookmark() }
        btnMore.setOnClickListener   { showOverflowMenu(it) }

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
                val wv = tabs.active?.webView
                if (wv != null && wv.canGoBack()) { wv.goBack(); return }
                tabs.active?.let {
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
        runCatching { tabs.active?.webView?.onPause(); tabs.active?.webView?.pauseTimers() }
    }

    override fun onResume() {
        super.onResume()
        runCatching { tabs.active?.webView?.onResume(); tabs.active?.webView?.resumeTimers() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // OS is asking us to free memory.  Drop disk cache from inactive WebViews
        // and trigger Java GC.
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            runCatching {
                for (t in tabs.tabs) if (t != tabs.active) t.webView.clearCache(false)
            }
        }
    }

    private fun submitAddress() {
        val url = UrlNormalizer.normalize(addr.text.toString())
        if (url.isBlank()) return
        val tab = tabs.active ?: tabs.newTab(url)
        tab.expectedOrigin = UrlNormalizer.origin(url) ?: tab.expectedOrigin
        tab.url = url
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
                val tab = tabs.active ?: tabs.newTab(item.url)
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
                val tab = tabs.active ?: tabs.newTab(item.url)
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
                Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showOverflowMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        val isStrict = settings.mode == Mode.Strict
        menu.menu.add(0, 1, 0, if (isStrict) "Mode: Strict (tap for Lenient)" else "Mode: Lenient (tap for Strict)")
        menu.menu.add(0, 2, 1, if (settings.adBlockEnabled) "Ad blocker: ON" else "Ad blocker: OFF")
        menu.menu.add(0, 8, 2, if (settings.historyEnabled) "History: ON" else "History: OFF")
        menu.menu.add(0, 9, 3, "Bookmarks")
        menu.menu.add(0, 10, 4, "History")
        menu.menu.add(0, 11, 5, "Clear history")
        menu.menu.add(0, 3, 6, "New tab")
        menu.menu.add(0, 4, 7, "Close current tab")
        menu.menu.add(0, 5, 8, "Reload")
        menu.menu.add(0, 6, 9, "Copy URL")
        menu.menu.add(0, 7, 10, "Share")
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

    override fun onLinkLongPressed(tab: Tab, linkUrl: String) {
        val items = arrayOf("Open in new tab", "Copy link")
        AlertDialog.Builder(this)
            .setTitle(linkUrl)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> tabs.newTab(linkUrl)
                    1 -> {
                        val cm = getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("link", linkUrl))
                        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
                    }
                }
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
                tab.expectedOrigin = UrlNormalizer.origin(nextUrl) ?: tab.expectedOrigin
                tab.webView.loadUrl(nextUrl)
            }
            .setNeutralButton("Always allow") { _, _ ->
                settings.allowOrigin(nextOrigin)
                Toast.makeText(this, "Always allowing $nextOrigin", Toast.LENGTH_SHORT).show()
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
