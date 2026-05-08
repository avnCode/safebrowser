package com.safebrowser.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : AppCompatActivity(), TabManager.Callbacks {

    private lateinit var addr: EditText
    private lateinit var btnBack: ImageButton
    private lateinit var btnFwd: ImageButton
    private lateinit var btnReload: ImageButton
    private lateinit var btnGo: Button
    private lateinit var btnNewTab: ImageButton
    private lateinit var btnMode: Button
    private lateinit var progress: ProgressBar
    private lateinit var webContainer: FrameLayout
    private lateinit var tabStrip: LinearLayout

    private lateinit var settings: Settings
    private lateinit var tabs: TabManager

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashLogger()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        readAndClearLastCrash()?.let { showCrashDialog(it) }

        settings    = Settings(this)
        addr        = findViewById(R.id.address)
        btnBack     = findViewById(R.id.btn_back)
        btnFwd      = findViewById(R.id.btn_forward)
        btnReload   = findViewById(R.id.btn_reload)
        btnGo       = findViewById(R.id.btn_go)
        btnNewTab   = findViewById(R.id.btn_new_tab)
        btnMode     = findViewById(R.id.btn_mode)
        progress    = findViewById(R.id.progress)
        webContainer= findViewById(R.id.web_container)
        tabStrip    = findViewById(R.id.tab_strip)

        tabs = TabManager(this, webContainer, tabStrip, this)

        btnBack.setOnClickListener   { tabs.active?.webView?.takeIf { it.canGoBack() }?.goBack() }
        btnFwd.setOnClickListener    { tabs.active?.webView?.takeIf { it.canGoForward() }?.goForward() }
        btnReload.setOnClickListener { tabs.active?.webView?.reload() }
        btnGo.setOnClickListener     { submitAddress() }
        btnNewTab.setOnClickListener { tabs.newTab() }
        btnMode.setOnClickListener   {
            settings.mode = if (settings.mode == Mode.Strict) Mode.Lenient else Mode.Strict
            refreshModeButton()
            Toast.makeText(this, "Mode: ${settings.mode.name}", Toast.LENGTH_SHORT).show()
        }

        addr.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                       actionId == EditorInfo.IME_ACTION_DONE ||
                       (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            if (isGo) { submitAddress(); true } else false
        }

        refreshModeButton()
        tabs.newTab(NEW_TAB_URL, activate = true)

        // If we were launched with a URL from another app, open it.
        intent?.dataString?.takeIf { it.isNotBlank() }?.let { tabs.newTab(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.takeIf { it.isNotBlank() }?.let { tabs.newTab(it) }
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
        btnMode.text = settings.mode.name
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val wv = tabs.active?.webView
            if (wv != null && wv.canGoBack()) { wv.goBack(); return true }
            // Otherwise close the active tab; if it was the last one a new home opens.
            tabs.active?.let { tabs.close(it); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---- TabManager.Callbacks ------------------------------------------

    override fun onActiveChanged(tab: Tab?) {
        if (tab == null) return
        addr.setText(if (tab.url.startsWith("file://")) "" else tab.url)
        progress.visibility = View.GONE
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
            .setMessage("From: $expected\n\nTo: $nextUrl\n\n$reason\n\nAllow?")
            .setPositiveButton("Allow once") { _, _ ->
                tab.expectedOrigin = UrlNormalizer.origin(nextUrl) ?: tab.expectedOrigin
                tab.webView.loadUrl(nextUrl)
            }
            .setNeutralButton("Open in new tab") { _, _ ->
                tabs.newTab(nextUrl)
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
