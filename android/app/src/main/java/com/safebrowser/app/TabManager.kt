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
    /** Set when the tab is hibernated; loaded back when re-activated. */
    var hibernatedUrl: String? = null
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

    private val seekThrottleJs: String by lazy {
        runCatching {
            ctx.assets.open("seek_throttle.js").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    private val bufferLimitJs: String by lazy {
        runCatching {
            ctx.assets.open("buffer_limit.js").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    private val videoTeardownJs: String by lazy {
        runCatching {
            ctx.assets.open("video_teardown.js").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    private val visibilityOverrideJs: String by lazy {
        runCatching {
            ctx.assets.open("visibility_override.js").bufferedReader().use { it.readText() }
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

    private var swapping = false

    fun activate(tab: Tab) {
        if (active == tab) return
        if (swapping) {
            // Coalesce — drop earlier swap, run the latest one on the next frame.
            container.removeCallbacks(pendingActivate)
        }
        pendingActivate = Runnable { doActivate(tab) }
        swapping = true
        container.post(pendingActivate)
    }

    private var pendingActivate: Runnable = Runnable {}

    private fun doActivate(tab: Tab) {
        swapping = false
        if (active == tab || !tabs.contains(tab)) return
        active?.let { old ->
            old.chip.isSelected = false
            runCatching { container.removeView(old.host) }
            runCatching { old.webView.onPause() }
            // Proactively free the old tab's memory so the renderer doesn't
            // hold two full pages simultaneously (~512 MB cap for one process).
            // If it's a cross-origin switch, hibernate outright to drop bfcache.
            val oldOrigin = UrlNormalizer.origin(old.url)
            val newOrigin = UrlNormalizer.origin(tab.hibernatedUrl ?: tab.url)
            if (oldOrigin != newOrigin && !oldOrigin.isNullOrEmpty()) {
                hibernate(old)
            } else {
                runCatching { old.webView.clearCache(true) }
                runCatching { old.webView.freeMemory() }
            }
        }
        active = tab
        tab.chip.isSelected = true
        runCatching { (tab.host.parent as? ViewGroup)?.removeView(tab.host) }
        runCatching { container.addView(tab.host) }
        runCatching { tab.webView.onResume() }
        // Wake hibernated tab.
        tab.hibernatedUrl?.let { saved ->
            tab.hibernatedUrl = null
            runCatching { tab.webView.loadUrl(saved) }
        }
        MemoryWatchdog.touchActive(tab.id)
        callbacks.onActiveChanged(tab)
    }

    fun close(tab: Tab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        runCatching { tabStrip.removeView(tab.chip) }
        tabs.remove(tab)
        if (active == tab) {
            runCatching { container.removeView(tab.host) }
            active = null
        }
        destroyWebViewSafely(tab.webView)
        if (tabs.isEmpty()) newTab(NEW_TAB_URL, true)
        else if (active == null) activate(tabs[idx.coerceAtMost(tabs.size - 1)])
    }

    /**
     * Destroying a WebView while it's still loading or while a renderer
     * callback is in flight crashes the app.  Stop everything, detach
     * listeners, then destroy on the next main-loop tick.
     */
    private fun destroyWebViewSafely(wv: WebView) {
        runCatching {
            wv.stopLoading()
            wv.webChromeClient = null
            wv.webViewClient = WebViewClient()
            wv.loadUrl("about:blank")
            wv.clearHistory()
            (wv.parent as? ViewGroup)?.removeView(wv)
        }
        wv.post { runCatching { wv.destroy() } }
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
            // On real memory pressure, hibernate the tab — keep its URL,
            // unload the page itself.  This is the single biggest renderer
            // memory win because all WebViews share one renderer process.
            if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) hibernate(t)
        }
    }

    /** Hibernate an inactive tab: park the WebView on about:blank, remember the URL. */
    fun hibernate(tab: Tab) {
        if (tab == active) return
        if (tab.hibernatedUrl != null) return
        val u = tab.url
        if (u.isBlank() || u == NEW_TAB_URL || u.startsWith("about:")) return
        tab.hibernatedUrl = u
        runCatching {
            tab.webView.stopLoading()
            tab.webView.loadUrl("about:blank")
            tab.webView.clearHistory()
        }
    }

    /** Hibernate every inactive tab right now (called when active tab loads heavy content). */
    fun hibernateAllInactive() {
        for (t in tabs) if (t != active) hibernate(t)
    }

    /**
     * Drop bfcache + per-WebView caches before a user-initiated cross-origin
     * navigation. The shared renderer process (~512MB cap) cannot hold the
     * previous page's full DOM/JS heap (kept alive by bfcache) PLUS the new
     * page being parsed/loaded — that overlap is what trips the OS killer
     * on the A -> B -> back -> C pattern. `clearHistory()` is the only call
     * that actually drops bfcache; `freeMemory()` and `clearCache()` do not.
     *
     * Caller must guarantee this is a USER-INITIATED cross-origin nav. Do
     * not call from `shouldOverrideUrlLoading` redirect path — that breaks
     * OAuth flows.  See Bug #15.
     */
    fun resetForNavigation(tab: Tab, newUrl: String) {
        if (newUrl == "about:blank") return
        runCatching { tab.webView.stopLoading() }
        runCatching { tab.webView.clearHistory() }
        runCatching { tab.webView.clearCache(true) }
        runCatching { tab.webView.clearFormData() }
        runCatching { tab.webView.freeMemory() }
    }

    /**
     * Go back safely: tear down video/audio elements on the current page
     * (releasing MediaCodec surfaces) BEFORE navigating, then clear history
     * after arrival to drop the page we just left from bfcache.
     *
     * Without this, bfcache holds the previous page's decoded video frames
     * (10–150 MB) alongside the destination page, tipping the single
     * renderer process over its ~512 MB cap.  Bug #16.
     */
    fun goBackSafely(tab: Tab) {
        val wv = tab.webView
        if (!wv.canGoBack()) return
        // 1. Tear down media elements synchronously so MediaCodec buffers
        //    are released before the back-navigation starts.
        if (videoTeardownJs.isNotBlank()) {
            runCatching { wv.evaluateJavascript(videoTeardownJs, null) }
        }
        // 2. Free caches that can be freed without nuking history (yet).
        runCatching { wv.clearCache(true) }
        runCatching { wv.freeMemory() }
        // 3. Navigate back.
        wv.goBack()
        // 4. Once the destination page finishes loading, clear history to
        //    evict the page we just left from bfcache. Posted so it runs
        //    after goBack() has taken effect on the next main-loop tick.
        wv.postDelayed({
            runCatching { wv.clearHistory() }
            runCatching { wv.freeMemory() }
        }, 600)
    }

    /** Diagnostic: back-forward list size for the active tab. */
    fun backForwardListSize(): Int =
        runCatching { active?.webView?.copyBackForwardList()?.size ?: 0 }.getOrDefault(0)

    /**
     * Replace a tab whose renderer process died with a fresh WebView, preserving
     * its host wrapper, chip, id and URL.  Without this the OS kills our whole
     * app when the WebView renderer OOMs (very common on video sites).
     */
    fun rebuildAfterCrash(tab: Tab) {
        val wasActive = (active == tab)
        val savedUrl = tab.url.takeUnless { it.isBlank() } ?: NEW_TAB_URL
        destroyWebViewSafely(tab.webView)
        val fresh = createWebView()
        runCatching { tab.host.removeAllViews() }
        runCatching {
            tab.host.addView(
                fresh,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
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
        // Tell Android: our renderer is IMPORTANT and should NOT be reaped
        // just because the activity is briefly off-screen.  This is the
        // single most effective lever we have against "page died randomly" —
        // by default the OS treats WebView renderers as discardable work.
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            runCatching {
                wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            }
        }
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
                if (allowed) {
                    // Free the previous page's renderer-side caches before
                    // the new page starts loading. Without this, single-tab
                    // cross-site navigation can spike memory enough for the
                    // OS to kill the renderer mid-handoff.
                    runCatching { view.freeMemory() }
                }
                return !allowed
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                val tab = view.tag as? Tab ?: return
                tab.url = url
                // Install the seek throttle BEFORE any <video> element exists,
                // so HTMLMediaElement.prototype.currentTime is wrapped before
                // the player constructs its media elements.  Prevents GPU
                // surface-pool exhaustion from rapid scrubbing.
                if (seekThrottleJs.isNotBlank()) {
                    view.evaluateJavascript(seekThrottleJs, null)
                }
                // Cap MSE forward buffer to ~25s so the compositor's tile
                // budget does not overflow and blank the page.  Must wrap
                // SourceBuffer.prototype.appendBuffer before the player
                // creates any SourceBuffer.
                if (bufferLimitJs.isNotBlank()) {
                    view.evaluateJavascript(bufferLimitJs, null)
                }
                // Override the Page Visibility API when background playback is
                // enabled so video players don't voluntarily pause on visibility
                // change.  Must be injected at onPageStarted before the player
                // registers its event listeners.
                if (settings.backgroundPlaybackEnabled && visibilityOverrideJs.isNotBlank()) {
                    view.evaluateJavascript(visibilityOverrideJs, null)
                }
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
