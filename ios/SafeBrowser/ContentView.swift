import SwiftUI

struct ContentView: View {
    @StateObject private var model = BrowserModel()
    @State private var addr: String = "https://duckduckgo.com/"

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 6) {
                Button(action: { model.goBack() })   { Image(systemName: "chevron.left") }
                Button(action: { model.goForward() }) { Image(systemName: "chevron.right") }
                Button(action: { model.reload() })   { Image(systemName: "arrow.clockwise") }
                TextField("Search or enter URL", text: $addr, onCommit: load)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled(true)
                    .keyboardType(.URL)
                    .padding(8)
                    .background(Color(.secondarySystemBackground))
                    .cornerRadius(8)
                Button("Go", action: load)
            }
            .padding(8)

            BrowserWebView(model: model)
                .onReceive(model.$currentURL) { url in
                    if let u = url { addr = u.absoluteString }
                }

            if let blocked = model.lastBlockedPopup {
                HStack {
                    Text("Popup blocked.").font(.footnote)
                    Spacer()
                    Button("Open manually") {
                        model.userLoad(blocked)
                        model.lastBlockedPopup = nil
                    }.font(.footnote)
                }
                .padding(8)
                .background(Color(.tertiarySystemBackground))
            }
        }
        .onAppear { load() }
    }

    private func load() {
        var s = addr.trimmingCharacters(in: .whitespaces)
        if !s.hasPrefix("http://") && !s.hasPrefix("https://") {
            if s.contains(" ") || !s.contains(".") {
                s = "https://duckduckgo.com/?q=" + (s.addingPercentEncoding(
                    withAllowedCharacters: .urlQueryAllowed) ?? s)
            } else {
                s = "https://" + s
            }
        }
        if let url = URL(string: s) { model.userLoad(url) }
    }
}
