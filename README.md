# SafeBrowser

A custom, minimal web browser for **macOS / Windows / Linux** (Electron) and **iPhone** (Swift + WKWebView), built to give you complete control over ads, popups, redirects, and new tabs.

> ⚠️ Reality check: a real HTML/CSS/JS engine takes thousands of engineer-years. Every alternative browser (Brave, Arc, Vivaldi, Firefox-iOS, etc.) wraps an existing engine — Chromium on desktop, WebKit on iOS (Apple mandates this). SafeBrowser does the same and stacks strict policy on top.

---

## Feature summary

| Capability | Desktop | iOS |
|---|---|---|
| Ad / tracker blocking (EasyList + EasyPrivacy) | ✅ Cliqz adblocker | ✅ Content Blocker JSON |
| Popups / `window.open` blocked | ✅ | ✅ |
| Page-initiated redirects blocked | ✅ | ✅ |
| Cross-origin redirect *prompt* (Allow once / Block) | ✅ | — |
| `<meta refresh>` stripped | ✅ | ✅ |
| JS `alert/confirm/prompt` suppressed | partial | ✅ |
| `beforeunload` ("are you sure?") suppressed | ✅ | n/a |
| Permission requests auto-denied | ✅ | n/a (WKWebView default) |
| Multi-tab UI | ✅ | (single tab v1) |
| HTML5 video fullscreen | ✅ | ✅ (default) |
| Strict / Lenient mode toggle | ✅ persisted | — |
| Custom new-tab page (wallpaper + Google search + mode toggle) | ✅ | — |

---

## Desktop (Electron)

### Run

```bash
cd web_browser/desktop
npm install
npm start
```

### Build

```bash
npm run build:mac     # or build:win / build:linux
```

### File map

- [desktop/main.js](web_browser/desktop/main.js) — main process: ad blocker, navigation policy, IPC, settings persistence.
- [desktop/preload-chrome.js](web_browser/desktop/preload-chrome.js) — IPC bridge for the chrome window (tabs / address bar / mode pill).
- [desktop/preload-webview.js](web_browser/desktop/preload-webview.js) — preload injected into every page; captures user link clicks and exposes a tiny settings API for the new-tab page.
- [desktop/renderer/index.html](web_browser/desktop/renderer/index.html) — chrome UI (tab strip, address bar, mode pill, redirect-confirm dialog).
- [desktop/renderer/renderer.js](web_browser/desktop/renderer/renderer.js) — tab management, navigation, toasts, prompts.
- [desktop/renderer/newtab.html](web_browser/desktop/renderer/newtab.html) — local home page (Unsplash wallpaper, Google search, mode toggle).

### How navigation policy works

There are **three** layers, applied in this order:

1. **Webview preload** ([preload-webview.js](web_browser/desktop/preload-webview.js))
   A capture-phase `click` / `auxclick` listener watches every `<a href="…">` activation. When the user clicks a link it calls `ipcMain.send('webview-link-click', href)`. Main records that URL as user-initiated and stores its origin as the *expected origin* for the next top-level navigation on that tab.

2. **Main-process `webRequest.onBeforeRequest`** for `mainFrame` requests
   Decides whether the network is allowed to fetch a top-level URL.
   - Allowed if the URL was explicitly approved (typed in the address bar, clicked link, or "Allow once" from a previous prompt).
   - Allowed if it matches the tab's expected origin (covers same-site server-side `302` chains).
   - Otherwise the request is **cancelled** and a `redirect-confirm` IPC message is sent to the renderer with a token, the expected origin, and the actual URL. The renderer shows a modal with **Block** / **Allow once**. "Allow once" calls `confirm-redirect` back to main, which marks the URL and re-issues the load.

3. **Webview `will-navigate` event**
   Catches client-side JS navigation that doesn't go through the network layer (e.g. SPA `history.pushState` followed by `location.assign`).
   - Same document / hash change → allow.
   - Lenient mode + same origin → allow (covers SPA routing on YouTube, Twitter/X, etc.).
   - Otherwise prevented and a `redirect-blocked` toast is shown.

### Strict vs Lenient

Persisted to `app.getPath('userData')/settings.json`. Toggle from the Mode pill in the chrome OR from the card on the new-tab page.

| Event | Strict | Lenient |
|---|---|---|
| Address-bar entry / Home button | ✅ allow | ✅ allow |
| Click in-site link | ✅ allow | ✅ allow |
| Click external link (direct) | ✅ allow | ✅ allow |
| Click → server `302` to **same** origin | ✅ allow | ✅ allow |
| Click → server `302` to **different** origin | ⚠ **prompt** | ⚠ **prompt** |
| Page-driven JS nav, same origin | ❌ block | ✅ allow |
| Page-driven JS nav, cross origin | ❌ block | ❌ block |
| `window.open` / popup | ❌ block | ❌ block |
| `<meta refresh>` | stripped | stripped |
| `beforeunload` prompt | suppressed | suppressed |

### Tabs

- Tab strip across the top with a **+** button.
- Each tab is a separate `<webview>` element; only the active one is visible.
- Cookies / logins are shared via the persistent partition `persist:safebrowser` so signing in once works everywhere.
- Closing the last tab automatically opens a new home tab.
- **Keyboard shortcuts**: `Cmd/Ctrl+T` new tab, `Cmd/Ctrl+W` close tab, `Cmd/Ctrl+L` focus address bar.

### Ad blocking

`@cliqz/adblocker-electron` is initialized with `ElectronBlocker.fromPrebuiltAdsAndTracking(fetch)`, which downloads the latest EasyList + EasyPrivacy and registers a session-wide `webRequest` filter plus cosmetic CSS injection. Lists are cached after first run.

### HTML5 video fullscreen

- Each `<webview>` has the `allowfullscreen` attribute.
- Main listens for `enter-html-full-screen` / `leave-html-full-screen` on the webview's `webContents` and toggles the OS window via `mainWindow.setFullScreen(...)`.

### URL bar behavior

`ipcMain.handle('normalize-url')` does the smart resolution:
- Already has a scheme → use as-is.
- Looks like a hostname (no whitespace, has a TLD) → prefix `https://`.
- Otherwise → Google search: `https://www.google.com/search?q=…`.

### New-tab page

[newtab.html](web_browser/desktop/renderer/newtab.html) is loaded from `file://` so we control the look:
- Full-bleed wallpaper from Unsplash with a dark gradient overlay.
- Centered Google-style wordmark.
- Search box that submits to Google (or navigates if input is a URL).
- Settings card with **Strict / Lenient** mode picker, hooked to the same persisted setting as the chrome's mode pill.

---

## iOS (Swift + WKWebView)

### Setup

1. Open Xcode → File → New → App. Name it `SafeBrowser`, select SwiftUI + Swift.
2. Drag the four `.swift` files from `web_browser/ios/SafeBrowser/` into the project.
3. Drag `Resources/ContentBlocker.json` into the project and ensure it's in **Target → Build Phases → Copy Bundle Resources**.
4. Run on a connected iPhone (free Apple ID = 7-day re-sign limit).

### File map

- [ios/SafeBrowser/SafeBrowserApp.swift](web_browser/ios/SafeBrowser/SafeBrowserApp.swift) — `@main` entry point.
- [ios/SafeBrowser/ContentView.swift](web_browser/ios/SafeBrowser/ContentView.swift) — SwiftUI view: nav buttons, address bar, banner for blocked popups.
- [ios/SafeBrowser/BrowserWebView.swift](web_browser/ios/SafeBrowser/BrowserWebView.swift) — `UIViewRepresentable` wrapping `WKWebView`. Implements navigation policy, popup denial, JS-dialog suppression.
- [ios/SafeBrowser/ContentBlockerLoader.swift](web_browser/ios/SafeBrowser/ContentBlockerLoader.swift) — compiles the JSON rules into a `WKContentRuleList`.
- [ios/SafeBrowser/Resources/ContentBlocker.json](web_browser/ios/SafeBrowser/Resources/ContentBlocker.json) — declarative ad/tracker blocking + cosmetic CSS hiding.

### How navigation policy works (iOS)

`WKNavigationDelegate.decidePolicyFor navigationAction:` branches on `navigationType`:

| navigationType | Decision |
|---|---|
| `.linkActivated` (user tap on `<a>`) | allow |
| `.formSubmitted` / `.formResubmitted` | allow |
| `.backForward` / `.reload` | allow |
| `.other` (page-initiated, JS, redirect) | allow only if URL was explicitly queued via `userLoad(_:)`; else **cancel** and surface as a "Popup blocked. Open manually?" banner |

`createWebViewWith` (the popup hook) **always returns nil**, denying every `window.open` / `target=_blank` request. JS `alert/confirm/prompt` are auto-dismissed.

A `WKUserScript` injected `atDocumentEnd` removes `<meta http-equiv="refresh">` tags and overwrites `window.open`. `mediaTypesRequiringUserActionForPlayback = .all` blocks autoplay (a common popup vector).

### Content blocker rules

[ContentBlocker.json](web_browser/ios/SafeBrowser/Resources/ContentBlocker.json) blocks the major ad/tracker hosts (DoubleClick, Google Ads, GA, GTM, AdNxs, Criteo, Taboola, Outbrain, Hotjar, Mixpanel, Segment, Branch, PopAds, PropellerAds, etc.), all `popup` resource-type loads, and uses `css-display-none` to hide common ad slot selectors (`ins.adsbygoogle`, `[class*=sponsored]`, etc.). Apple's hard cap is 150,000 rules per list.

---

## Customizing

### Add / remove blocked hosts
- **Desktop**: replace `ElectronBlocker.fromPrebuiltAdsAndTracking(fetch)` with `ElectronBlocker.fromLists(fetch, [url1, url2, …])` to use custom EasyList-format rule sources.
- **iOS**: append objects to `ContentBlocker.json`. Each rule needs `trigger` and `action` (`block`, `block-cookies`, `css-display-none`, `make-https`).

### Change the wallpaper
Edit the `background:` rule in [newtab.html](web_browser/desktop/renderer/newtab.html) to point at any URL or local image (drop the file in `desktop/renderer/`).

### Change the default search engine
Edit `SEARCH_URL` in [main.js](web_browser/desktop/main.js) and the fallback in [newtab.html](web_browser/desktop/renderer/newtab.html).

### Tighten or relax the redirect prompt
The cross-origin prompt is in `webRequest.onBeforeRequest` inside `applyHardening()` in [main.js](web_browser/desktop/main.js). To never prompt and always block, replace the `pendingConfirms.set(...)` block with an unconditional `callback({ cancel: true })`. To suppress prompts entirely in Lenient mode, gate the prompt on `if (settings.mode === 'strict')`.

---

## Architecture diagram (desktop)

```
+---------------------- BrowserWindow ----------------------+
|  preload-chrome.js  ←→  IPC  ←→  main.js                  |
|        ↑                            ↓                     |
|     renderer.js                onBeforeRequest            |
|     (tabs UI,                  (cross-origin prompt)      |
|      mode pill,                will-navigate              |
|      redirect modal)           (SPA routing policy)       |
|        ↓                       setWindowOpenHandler       |
|   ┌─ <webview> tab 1 ─┐         (popup denial)            |
|   │  preload-webview  │         enter-html-full-screen    |
|   │  (link clicks →   │         (video fullscreen)        |
|   │   IPC)            │                                   |
|   └───────────────────┘                                   |
|   ┌─ <webview> tab 2 ─┐                                   |
|   └───────────────────┘                                   |
+-----------------------------------------------------------+
                ↓
       Cliqz adblocker session
       (EasyList + EasyPrivacy)
```

---

## Roadmap

- iOS: tabs and a Strict/Lenient toggle.
- Bookmarks + history (on-device only).
- HTTPS-only mode and DNS-over-HTTPS.
- Per-site permission overrides UI.
- Allow-list of "trusted cross-origin redirect" sites so common shorteners (`t.co`, `bit.ly`) don't prompt every time.

## Features

| Feature | Where in README |
|---|---|
| **Strict mode** | Feature matrix (top), full "Strict vs Lenient" event table, persisted-settings note |
| **Lenient mode** | Same table — explicitly shows which events relax (same-origin SPA nav allowed, cross-origin still prompts) |
| **Mode toggle UI** (chrome pill + new-tab card) | "Strict vs Lenient" section + "New-tab page" section |
| **Settings persistence** to `userData/settings.json` | "Strict vs Lenient" section |
| **Multi-tab UI** | Feature matrix + dedicated "Tabs" section + keyboard shortcuts |
| **Cross-origin redirect prompt** (Allow once / Block) | Feature matrix + "How navigation policy works" layer 2 + Strict/Lenient table |
| **Link click capture via webview preload** | "How navigation policy works" layer 1 |
| **`will-navigate` SPA policy** | "How navigation policy works" layer 3 |
| **Ad/tracker blocking** (Cliqz + EasyList/EasyPrivacy) | Feature matrix + "Ad blocking" section |
| **Popup blocking** (`setWindowOpenHandler`) | Feature matrix + nav policy section |
| **`<meta refresh>` strip + `window.open` neutralize** | Feature matrix + iOS section |
| **`beforeunload` suppression** | Feature matrix |
| **Permission auto-deny** | Feature matrix |
| **HTML5 video fullscreen** | Feature matrix + dedicated section |
| **URL bar normalization + Google as default search** | "URL bar behavior" section |
| **Custom new-tab page** (Unsplash wallpaper, search, mode card) | "New-tab page" section |
| **Cookie/login persistence** via `persist:safebrowser` | "Tabs" section |
| **Keyboard shortcuts** (Cmd+T/W/L) | "Tabs" section |
| **iOS — `WKNavigationDelegate` policy** | iOS section with full navigationType table |
| **iOS — Content Blocker rules + cosmetic CSS** | iOS section |
| **iOS — JS dialog suppression, autoplay block, popup deny** | iOS section |
| **Customization recipes** (blocklists, wallpaper, search, prompt strictness) | "Customizing" section |
| **Architecture diagram** | ASCII diagram at the bottom |
| **Roadmap** | Final section |
