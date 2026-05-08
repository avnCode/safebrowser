import Foundation
import WebKit

/// Loads ContentBlocker.json (Apple-format declarative rules) and compiles them
/// into a WKContentRuleList attached to the WebView's user content controller.
enum ContentBlockerLoader {
    static func apply(to ucc: WKUserContentController) {
        guard let url = Bundle.main.url(forResource: "ContentBlocker", withExtension: "json"),
              let json = try? String(contentsOf: url, encoding: .utf8) else {
            print("ContentBlocker.json missing — skipping rule compilation")
            return
        }
        WKContentRuleListStore.default()?.compileContentRuleList(
            forIdentifier: "SafeBrowserRules",
            encodedContentRuleList: json
        ) { ruleList, error in
            if let error = error { print("Rule compile error: \(error)"); return }
            if let ruleList = ruleList { ucc.add(ruleList) }
        }
    }
}
