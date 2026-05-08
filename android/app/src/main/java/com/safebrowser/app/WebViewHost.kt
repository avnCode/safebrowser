package com.safebrowser.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

private const val CHROME_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

/**
 * One persistent WebView per tab.  The container swaps which WebView is
 * visible when the active tab changes.  We keep them around so that switching
 * tabs doesn't reload pages.
 */
@SuppressLint("SetJavaScriptEnabled")
class TabWebView(
    val tabId: Long,
    private val vm: BrowserViewModel,
    context: android.content.Context,
) : WebView(context) {

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled   = true
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = CHROME_UA
            // Pinch zoom
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

        webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) vm.setTabTitle(tabId, title)
            }
        }

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val tab = vm.tabs.value.firstOrNull { it.id == tabId } ?: return false
                val allowed = vm.shouldAllowNavigation(tab, req.url.toString(), req.hasGesture())
                return !allowed // returning true blocks the load
            }

            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val pageUrl = view.url
                val s = vm.settings.value
                if (vm.adBlocker.shouldBlock(pageUrl, req, s.adblock)) {
                    return vm.adBlocker.emptyResponse()
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                vm.setTabUrl(tabId, url); vm.setTabLoading(tabId, true)
            }
            override fun onPageFinished(view: WebView, url: String) {
                vm.setTabUrl(tabId, url); vm.setTabLoading(tabId, false)
            }
        }
    }

    /** Load a URL only if it's different from what's already showing. */
    fun loadIfChanged(url: String) {
        if (this.url != url) loadUrl(url)
    }
}

/**
 * Renders the active tab.  Hosts a per-tab WebView inside an AndroidView,
 * keyed by tab id so Compose recreates the host when switching tabs.
 */
@Composable
fun WebViewHost(vm: BrowserViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val tabs by vm.tabs.collectAsState()
    val activeId by vm.activeTabId.collectAsState()
    val tab = tabs.firstOrNull { it.id == activeId } ?: return

    // Cache one WebView per tab id, lifetime tied to this composition.
    val cache = remember { mutableMapOf<Long, TabWebView>() }
    val webView = cache[tab.id] ?: runCatching {
        val wv = TabWebView(tab.id, vm, ctx)
        vm.registerBack(tab.id) {
            if (wv.canGoBack()) { wv.goBack(); true } else false
        }
        cache[tab.id] = wv
        wv
    }.getOrElse {
        android.util.Log.e("SafeBrowser", "WebView init failed", it)
        androidx.compose.material3.Text(
            "Browser engine failed to start.\n${it.javaClass.simpleName}: ${it.message}\n\n" +
            "Open Play Store and update \"Android System WebView\".",
            modifier = modifier,
        )
        return
    }

    DisposableEffect(tabs) {
        val keep = tabs.map { it.id }.toSet()
        val gone = cache.keys.filterNot { it in keep }
        for (id in gone) {
            cache[id]?.let { wv ->
                vm.unregisterBack(id)
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            }
            cache.remove(id)
        }
        onDispose { }
    }

    key(tab.id) {
        AndroidView(
            modifier = modifier,
            factory = { _ ->
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView
            },
            update = { it.loadIfChanged(tab.url) },
        )
    }
}

