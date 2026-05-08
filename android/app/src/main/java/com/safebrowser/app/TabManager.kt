package com.safebrowser.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

const val NEW_TAB_URL = "file:///android_asset/newtab.html"

private const val CHROME_UA =
    "Mozilla/5.0 (Linux; Android 13; SM-X716B) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

class Tab(val id: Long, val webView: WebView, val chip: View, val title: TextView) {
    var url: String = ""
    var pageTitle: String = "New Tab"
    var expectedOrigin: String? = null
}

/**
 * Owns all tabs.  Each tab is its own WebView; the active one is the only
 * child of [container].
 */
class TabManager(
    private val ctx: Context,
    private val container: FrameLayout,
    private val tabStrip: LinearLayout,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onActiveChanged(tab: Tab?)
        fun onProgress(tab: Tab, progress: Int)
        fun onUrlChanged(tab: Tab)
        fun shouldAllowNavigation(tab: Tab, nextUrl: String, isUserGesture: Boolean): Boolean
        fun onPopupBlocked()
        fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback)
        fun onHideFullscreen()
        fun onLinkLongPressed(tab: Tab, linkUrl: String)
    }

    private var nextId = 1L
    val tabs = mutableListOf<Tab>()
    var active: Tab? = null
        private set

    fun newTab(url: String = NEW_TAB_URL, activate: Boolean = true): Tab {
        val wv = createWebView()
        val chip = LayoutInflater.from(ctx).inflate(R.layout.tab_chip, tabStrip, false)
        val titleView = chip.findViewById<TextView>(R.id.tab_title)
        val closeBtn  = chip.findViewById<ImageButton>(R.id.tab_close)

        val tab = Tab(nextId++, wv, chip, titleView)
        tab.url = url
        tab.expectedOrigin = UrlNormalizer.origin(url)
        wv.tag = tab

        chip.setOnClickListener { activate(tab) }
        closeBtn.setOnClickListener { close(tab) }

        tabStrip.addView(chip)
        tabs.add(tab)
        wv.loadUrl(url)

        if (activate) activate(tab)
        return tab
    }

    fun activate(tab: Tab) {
        if (active == tab) return
        active?.let {
            it.chip.isSelected = false
            container.removeView(it.webView)
        }
        active = tab
        tab.chip.isSelected = true
        if (tab.webView.parent != null) (tab.webView.parent as ViewGroup).removeView(tab.webView)
        container.addView(tab.webView)
        callbacks.onActiveChanged(tab)
    }

    fun close(tab: Tab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        tabStrip.removeView(tab.chip)
        tabs.remove(tab)
        if (active == tab) {
            container.removeView(tab.webView)
            active = null
        }
        tab.webView.destroy()
        if (tabs.isEmpty()) newTab(NEW_TAB_URL, true)
        else if (active == null) activate(tabs[idx.coerceAtMost(tabs.size - 1)])
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(ctx)
        wv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = CHROME_UA
        }

        // Long-press on a link → context menu (Open in new tab / Copy link).
        wv.setOnLongClickListener {
            val res = wv.hitTestResult
            val type = res.type
            val link = res.extra
            if (!link.isNullOrBlank() && (
                    type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                    type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) {
                val tab = wv.tag as? Tab
                if (tab != null) { callbacks.onLinkLongPressed(tab, link); true } else false
            } else false
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean {
                callbacks.onPopupBlocked()
                return false
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                val tab = view.tag as? Tab ?: return
                callbacks.onProgress(tab, newProgress)
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                val tab = view.tag as? Tab ?: return
                tab.pageTitle = title?.ifBlank { tab.url } ?: tab.url
                tab.title.text = tab.pageTitle
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                callbacks.onShowFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                callbacks.onHideFullscreen()
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val tab = view.tag as? Tab ?: return false
                val allowed = callbacks.shouldAllowNavigation(tab, req.url.toString(), req.hasGesture())
                return !allowed
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                val tab = view.tag as? Tab ?: return
                tab.url = url
                callbacks.onUrlChanged(tab)
            }

            override fun onPageFinished(view: WebView, url: String) {
                val tab = view.tag as? Tab ?: return
                tab.url = url
                // Strip popup-opener once page is done loading.
                view.evaluateJavascript("try{window.open=function(){return null};}catch(e){}", null)
                callbacks.onUrlChanged(tab)
            }
        }
        return wv
    }
}
