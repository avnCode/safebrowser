package com.safebrowser.app

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewRenderProcess
import androidx.webkit.WebViewRenderProcessClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

const val NEW_TAB_URL = "file:///android_asset/newtab.html"
const val MAX_TABS = 8

private const val CHROME_UA =
    "Mozilla/5.0 (Linux; Android 13; SM-X716B) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

class Tab(
    val id: Long,
    var webView: WebView,
    val host: SwipeRefreshLayout,
    val chip: View,
    val title: TextView,
) {
    var url: String = ""
    var pageTitle: String = "New Tab"
    var expectedOrigin: String? = null
}

class TabManager(
    private val ctx: Context,
    private val container: FrameLayout,
    private val tabStrip: LinearLayout,
    private val callbacks: Callbacks,
    private val adBlocker: AdBlocker,
    private val settings: Settings,
) {
    interface Callbacks {
        fun onActiveChanged(tab: Tab?)
        fun onProgress(tab: Tab, progress: Int)
        fun onUrlChanged(tab: Tab)
        fun shouldAllowNavigation(tab: Tab, nextUrl: String, isUserGesture: Boolean): Boolean
        fun onPopupBlocked()
        fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback)
        fun onHideFullscreen()
        fun onLinkLongPressed(tab: Tab, linkUrl: String, imageUrl: String?)
        fun onDownloadRequested(
            url: String, userAgent: String?, contentDisposition: String?,
            mimeType: String?, contentLength: Long,
        )
        fun onRendererCrashed(tab: Tab, crashed: Boolean)
    }

    private var nextId = 1L
    val tabs = mutableListOf<Tab>()
    var active: Tab? = null
        private set

    private val overlayZapperJs: String by lazy {
        runCatching {
            ctx.assets.open("overlay_zapper.js").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    fun newTab(url: String = NEW_TAB_URL, activate: Boolean = true): Tab? {
        if (tabs.size >= MAX_TABS) {
            Toast.makeText(ctx, "Tab limit ($MAX_TABS). Close one first.", Toast.LENGTH_SHORT).show()
            return null
        }
        val wv = createWebView()
        val host = SwipeRefreshLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            addView(wv,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT)
            setOnRefreshListener {
                wv.reload()
                postDelayed({ isRefreshing = false }, 1500)
            }
        }
        val chip = LayoutInflater.from(ctx).inflate(R.layout.tab_chip, tabStrip, false)
        val titleView = chip.findViewById<TextView>(R.id.tab_title)
        val closeBtn  = chip.findViewById<ImageButton>(R.id.tab_close)

        val tab = Tab(nextId++, wv, host, chip, titleView)
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
            container.removeView(it.host)
            runCatching { it.webView.onPause() }
        }
        active = tab
        tab.chip.isSelected = true
        if (tab.host.parent != null) (tab.host.parent as ViewGroup).removeView(tab.host)
        container.addView(tab.host)
        runCatching { tab.webView.onResume() }
        callbacks.onActiveChanged(tab)
    }

    fun close(tab: Tab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        tabStrip.removeView(tab.chip)
        tabs.remove(tab)
        if (active == tab) {
            container.removeView(tab.host)
            active = null
        }
        runCatching { (tab.webView.parent as? ViewGroup)?.removeView(tab.webView) }
        tab.webView.destroy()
        if (tabs.isEmpty()) newTab(NEW_TAB_URL, true)
        else if (active == null) activate(tabs[idx.coerceAtMost(tabs.size - 1)])
    }

    fun pauseActive() {
        runCatching { active?.webView?.onPause() }
    }
    fun resumeActive() {
        runCatching { active?.webView?.onResume() }
    }

    /** Aggressively free memory for inactive tabs. */
    fun trimInactive(level: Int) {
        for (t in tabs) if (t != active) {
            runCatching {
                t.webView.clearCache(level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE)
                t.webView.freeMemory()
            }
        }
    }

    /**
     * Replace a tab whose renderer process died with a fresh WebView, preserving
     * its host wrapper, chip, id and URL.  Without this the OS kills our whole
     * app when the WebView renderer OOMs (very common on video sites).
     */
    fun rebuildAfterCrash(tab: Tab) {
        val wasActive = (active == tab)
        val savedUrl = tab.url.takeUnless { it.isBlank() } ?: NEW_TAB_URL
        runCatching {
            (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
            tab.webView.destroy()
        }
        val fresh = createWebView()
        tab.host.removeAllViews()
        tab.host.addView(
            fresh,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        tab.webView = fresh
        fresh.tag = tab
        if (wasActive) runCatching { fresh.onResume() }
        fresh.loadUrl(savedUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(ctx)
        wv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        // Force a hardware layer so video frames are GPU-composited.  Without
        // this the WebView can fall back to a software layer (especially when
        // wrapped in SwipeRefreshLayout), which OOMs the renderer on video.
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            userAgentString = CHROME_UA
            // ---- Memory-pressure mitigations (renderer crashes on video sites) ----
            // Don't pre-rasterize tiles outside the viewport (default false, but be explicit).
            offscreenPreRaster = false
            // Disable file:// access from network pages.
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            // Smaller image cache footprint on big pages.
            blockNetworkImage = false
            loadsImagesAutomatically = true
        }
        // Disable Safe Browsing — it spawns a separate service process and on
        // memory-tight devices that pressure contributes to renderer OOM kills.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            runCatching { androidx.webkit.WebSettingsCompat.setSafeBrowsingEnabled(wv.settings, false) }
        }

        // Detect a hung renderer and terminate it ourselves *before* the OS
        // does it violently — this turns a hard crash into a clean reload.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE)) {
            WebViewCompat.setWebViewRenderProcessClient(
                wv,
                object : WebViewRenderProcessClient() {
                    override fun onRenderProcessUnresponsive(
                        view: WebView,
                        renderer: WebViewRenderProcess?,
                    ) {
                        // Renderer hasn't pumped events for ~5s. Kill it so
                        // onRenderProcessGone fires and we rebuild cleanly.
                        runCatching { renderer?.terminate() }
                    }
                    override fun onRenderProcessResponsive(
                        view: WebView,
                        renderer: WebViewRenderProcess?,
                    ) {}
                },
            )
        }

        wv.setOnLongClickListener {
            val res = wv.hitTestResult
            val type = res.type
            val extra = res.extra
            val tab = wv.tag as? Tab
            if (tab == null || extra.isNullOrBlank()) return@setOnLongClickListener false
            when (type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                    callbacks.onLinkLongPressed(tab, extra, null); true
                }
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    callbacks.onLinkLongPressed(tab, extra, extra); true
                }
                WebView.HitTestResult.IMAGE_TYPE -> {
                    callbacks.onLinkLongPressed(tab, extra, extra); true
                }
                else -> false
            }
        }

        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            callbacks.onDownloadRequested(url, userAgent, contentDisposition, mimeType, contentLength)
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
                if (newProgress >= 100) tab.host.isRefreshing = false
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
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val url = req.url?.toString()
                if (adBlocker.shouldBlock(url)) {
                    adBlocker.noteBlocked()
                    return adBlocker.emptyResponse()
                }
                return null
            }

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
                tab.host.isRefreshing = false
                // Only run the overlay zapper if explicitly enabled.  Per-page JS
                // injection is otherwise avoided \u2014 we already block popups via
                // setSupportMultipleWindows(false) + javaScriptCanOpenWindowsAutomatically=false.
                if (settings.overlayBlockerEnabled && overlayZapperJs.isNotBlank()) {
                    view.evaluateJavascript(overlayZapperJs, null)
                }
                callbacks.onUrlChanged(tab)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // The page's process died (OOM in video decoder, GPU crash, etc.).
                // Rebuild the WebView so the host app stays alive.  Returning true
                // tells the framework we handled it; otherwise it kills our process.
                val tab = view.tag as? Tab
                val crashed = if (android.os.Build.VERSION.SDK_INT >= 26) detail.didCrash() else true
                if (tab != null) {
                    view.post {
                        runCatching { rebuildAfterCrash(tab) }
                        callbacks.onRendererCrashed(tab, crashed)
                    }
                }
                return true
            }
        }
        return wv
    }
}
