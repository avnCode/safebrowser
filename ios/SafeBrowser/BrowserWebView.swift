import SwiftUI
import WebKit

/// Holds shared state and exposes navigation commands to SwiftUI.
final class BrowserModel: ObservableObject {
    @Published var currentURL: URL?
    @Published var lastBlockedPopup: URL?

    /// URLs the user explicitly asked to load. Anything else is page-initiated.
    var pendingUserURLs: Set<URL> = []
    weak var webView: WKWebView?

    func userLoad(_ url: URL) {
        pendingUserURLs.insert(url)
        webView?.load(URLRequest(url: url))
    }
    func goBack()    { webView?.goBack() }
    func goForward() { webView?.goForward() }
    func reload()    { webView?.reload() }
}

struct BrowserWebView: UIViewRepresentable {
    @ObservedObject var model: BrowserModel

    func makeCoordinator() -> Coordinator { Coordinator(model: model) }

    func makeUIView(context: Context) -> WKWebView {
        let cfg = WKWebViewConfiguration()
        // No JS-driven new windows; we deny in uiDelegate too.
        cfg.preferences.javaScriptCanOpenWindowsAutomatically = false
        cfg.defaultWebpagePreferences.allowsContentJavaScript = true
        // Block media autoplay (common popup vector).
        cfg.mediaTypesRequiringUserActionForPlayback = .all

        // Strip meta-refresh tags after each page load.
        let stripMeta = WKUserScript(source: """
            (function(){
              document.querySelectorAll('meta[http-equiv="refresh" i]').forEach(m=>m.remove());
              try { window.open = function(){ return null; }; } catch(_){}
            })();
        """, injectionTime: .atDocumentEnd, forMainFrameOnly: false)
        cfg.userContentController.addUserScript(stripMeta)

        // Apply Safari-style content blocker rules (ads/trackers/popup hosts).
        ContentBlockerLoader.apply(to: cfg.userContentController)

        let wv = WKWebView(frame: .zero, configuration: cfg)
        wv.navigationDelegate = context.coordinator
        wv.uiDelegate = context.coordinator
        wv.allowsBackForwardNavigationGestures = true
        model.webView = wv
        return wv
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        let model: BrowserModel
        init(model: BrowserModel) { self.model = model }

        // Decide whether to allow each navigation. Block JS-driven mainFrame redirects.
        func webView(_ webView: WKWebView,
                     decidePolicyFor navigationAction: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let url = navigationAction.request.url else {
                decisionHandler(.cancel); return
            }
            // Always allow same-document fragment navigations.
            if let cur = webView.url,
               cur.scheme == url.scheme, cur.host == url.host, cur.path == url.path {
                decisionHandler(.allow); return
            }
            switch navigationAction.navigationType {
            case .linkActivated, .formSubmitted, .formResubmitted, .backForward, .reload:
                decisionHandler(.allow)
            case .other:
                // Page-initiated. Allow only if the user explicitly queued this URL.
                if model.pendingUserURLs.remove(url) != nil {
                    decisionHandler(.allow)
                } else {
                    DispatchQueue.main.async { self.model.lastBlockedPopup = url }
                    decisionHandler(.cancel)
                }
            @unknown default:
                decisionHandler(.cancel)
            }
        }

        func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
            DispatchQueue.main.async { self.model.currentURL = webView.url }
        }

        // Deny ALL requests to open new windows. Surface as "blocked popup" toast.
        func webView(_ webView: WKWebView,
                     createWebViewWith configuration: WKWebViewConfiguration,
                     for navigationAction: WKNavigationAction,
                     windowFeatures: WKWindowFeatures) -> WKWebView? {
            if let url = navigationAction.request.url {
                DispatchQueue.main.async { self.model.lastBlockedPopup = url }
            }
            return nil
        }

        // Suppress JS dialogs (alert/confirm/prompt) — common in popup/scam pages.
        func webView(_ webView: WKWebView,
                     runJavaScriptAlertPanelWithMessage message: String,
                     initiatedByFrame frame: WKFrameInfo,
                     completionHandler: @escaping () -> Void) { completionHandler() }
        func webView(_ webView: WKWebView,
                     runJavaScriptConfirmPanelWithMessage message: String,
                     initiatedByFrame frame: WKFrameInfo,
                     completionHandler: @escaping (Bool) -> Void) { completionHandler(false) }
        func webView(_ webView: WKWebView,
                     runJavaScriptTextInputPanelWithPrompt prompt: String,
                     defaultText: String?,
                     initiatedByFrame frame: WKFrameInfo,
                     completionHandler: @escaping (String?) -> Void) { completionHandler(nil) }
    }
}
