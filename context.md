# SafeBrowser — Context & Engineering Notes

A custom Android WebView browser for Galaxy Tab S9 (Android 16 / One UI 8).
This file is the canonical record of *why* the code looks the way it does — bugs
encountered, root causes, fixes shipped, and the structural constraints that
shaped the architecture.

---

## 1. Project facts

- **Repo**: `avnCode/safebrowser` (private)
- **Module path**: `web_browser/android/`
- **Package**: `com.safebrowser.app`
- **Target device**: Galaxy Tab S9 (Android 16, One UI 8)
- **Build**: Kotlin 1.9.24, AGP 8.6.1, Gradle 8.7, JVM 17
- **SDK**: `compileSdk=35`, `targetSdk=35`, `minSdk=26`
- **UI stack**: AppCompatActivity + plain XML Views (NO Compose — see Bug #0)
- **Theme**: `Theme.Material3.DayNight.NoActionBar`
- **CI**: GitHub Actions (`.github/workflows/android.yml`) → debug APK attached to GitHub Release on tag push
- **Latest released tag**: `v1.0.28`

### Key dependencies

| Lib                                                | Why                                    |
| -------------------------------------------------- | -------------------------------------- |
| `androidx.core:core-ktx:1.13.1`                    | Core KTX                               |
| `androidx.appcompat:appcompat:1.7.0`               | AppCompatActivity                      |
| `com.google.android.material:material:1.12.0`      | Material 3 widgets                     |
| `androidx.webkit:webkit:1.11.0`                    | `WebViewCompat`, render process client |
| `androidx.swiperefreshlayout:swiperefreshlayout:1.1.0` | Pull-to-refresh                    |

### Manifest highlights

- `largeHeap="true"` — needed for video pages
- `hardwareAccelerated="true"` — required for video composition
- `enableOnBackInvokedCallback="true"` — Android 13+ predictive back
- `usesCleartextTraffic="false"` — security
- Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `WRITE_EXTERNAL_STORAGE` (maxSdkVersion=28)

---

## 2. Architecture cheat sheet

- **Single activity** (`MainActivity`) implementing `TabManager.Callbacks`
- **`TabManager`** owns all tabs, the active tab, and central WebView config
- **`Tab`** holds: `id`, `webView` (var, swappable on renderer crash), `host` (SwipeRefreshLayout wrapping the WebView), `chip`, `title`, `url`, `pageTitle`, `expectedOrigin`, `hibernatedUrl`
- **Layout**: vertical `LinearLayout` →
  - tab strip (`HorizontalScrollView` + `LinearLayout`)
  - toolbar row (back / fwd / [reload | URL bar | shield] / bookmark / new-tab / more)
  - `ProgressBar` (gone unless loading)
  - `FrameLayout web_container` — holds the active tab's host
- **Persistence**:
  - `SharedPreferences("safebrowser")` — settings, blocked/allowed origins
  - `filesDir/bookmarks.json`, `filesDir/history.json` (max 1000)
  - `filesDir/last_crash.txt` — JVM crash log (shown on next launch)
- **Tab cap**: `MAX_TABS = 8`

### Files

| File                    | Purpose                                                                 |
| ----------------------- | ----------------------------------------------------------------------- |
| `MainActivity.kt`       | UI, callbacks, lifecycle, overflow menu, dialogs, crash logger          |
| `TabManager.kt`         | Tabs, WebView config, crash recovery, hibernation                       |
| `Settings.kt`           | SharedPreferences-backed settings + allow/block origin lists            |
| `UrlNormalizer.kt`      | `normalize`, `host`, `origin`, `sameRegistrableDomain`                  |
| `AdBlocker.kt`          | Loads `assets/blocklist.txt`, `shouldBlock`, returns blank response     |
| `Storage.kt`            | `Bookmarks`, `History`                                                  |
| `Downloader.kt`         | DownloadManager wrapper with cookies + UA + Referer                     |
| `assets/newtab.html`    | Home page (Unsplash bg, SafeGo wordmark, Google search)                 |
| `assets/blocklist.txt`  | ~50 ad/tracker hosts                                                    |
| `assets/overlay_zapper.js` | Heuristic overlay/cookie/paywall hider, MutationObserver active 15s |
| `assets/seek_throttle.js` | Coalesces rapid `video.currentTime` writes (see Bug #11)              |

---

## 3. Bug log — root causes and fixes

Numbered by severity, not chronology. Every entry below took a real debugging
session; do not undo these changes without re-reading the entry.

### Bug #0 — Compose crash on launch (v1.0.0–1.0.1)

**Symptom**: app died on first frame on real device.
**Root cause**: Jetpack Compose runtime + WebView interop on this device hit
a Compose layout exception under `Theme.Material3`.
**Fix**: dropped Compose entirely; rewrote in plain XML + AppCompatActivity.
**Lesson**: Compose is fine for greenfield, but mixing with WebView on older
device profiles is fragile. Stay on classic Views for this app.

### Bug #1 — `?attr/colorBackground` doesn't exist (v1.0.10 → v1.0.12)

**Symptom**: build broke in CI.
**Root cause**: `?attr/colorBackground` is not a Material 3 attr — it's a
platform attr.
**Fix**: `?android:attr/colorBackground` in `activity_main.xml`.
**Rule of thumb**: `?attr/...` is for `colorPrimary`, `colorSurfaceVariant`,
`colorOnSurface`, etc. (Material attrs). `?android:attr/...` is for
`colorBackground`, `windowBackground`, etc. (platform attrs).

### Bug #2 — Back button minimised app instead of going back (v1.0.8)

**Symptom**: Pressing back never navigated, just sent app to recents.
**Root cause**: With `enableOnBackInvokedCallback=true` (Android 13+
predictive back), `onKeyDown(KEYCODE_BACK)` is never delivered.
**Fix**: Use `OnBackPressedDispatcher.addCallback` and handle the cascade:
fullscreen → `webView.goBack()` → close non-last tab → `moveTaskToBack(true)`.

### Bug #3 — Cross-site link confirmations spammed user (v1.0.4–v1.0.9)

**Symptom**: Tapping any link asked "open in same tab?".
**Root cause**: Initial nav prompt did not respect `request.hasGesture()`.
**Fix**: Only prompt when origin changes AND the navigation isn't a user
gesture. Style the dialog as button options (Open in new tab / Stay / Cancel).

### Bug #4 — Video sites froze, then app vanished (v1.0.13)

**Symptom**: Video player stops responding → app silently disappears, no
logcat from our code.
**Root cause**: WebView renderer process OOM'd in the video decoder. By
default, when the renderer dies, the framework kills the host app too.
JVM crash logger (`Thread.setDefaultUncaughtExceptionHandler`) never sees it
because the death is in the *native* sandboxed renderer process.
**Initial fix (v1.0.13)**: mixed-content compat mode, autoplay allowed,
pause inactive tabs, `onTrimMemory`, `largeHeap=true`, `hardwareAccelerated=true`.
Helped but didn't eliminate.
**Real fix**: see Bug #5.

### Bug #5 — Renderer death killed the host app (v1.0.16) ⭐

**Symptom**: same as #4 — video freezes, app vanishes silently.
**Root cause**: We never overrode `WebViewClient.onRenderProcessGone()`.
Default behavior is to kill our host process when the renderer dies.
**Fix**:
- Override `onRenderProcessGone(view, detail): Boolean`, return `true` to
  signal "we handled it; don't kill us".
- In the handler, call `rebuildAfterCrash(tab)` which destroys the dead
  WebView and creates a fresh one inside the same `SwipeRefreshLayout` host,
  preserving tab id/chip/url, then `loadUrl(savedUrl)`.
- Surface a Toast: "Renderer crashed — page reloaded".
- Made `Tab.webView` a `var` so it's swappable in place.
- Added `Callbacks.onRendererCrashed(tab, crashed)` to MainActivity.

**This is the canonical Chromium-team-recommended pattern** — it's why Chrome
itself doesn't die when a tab OOMs.

### Bug #6 — Renderer kept crashing on video sites (v1.0.17 attempt) ⭐

**Symptom**: even after Bug #5 fix, renderer kept dying repeatedly.
**Root cause**: actually a *combination* of issues that v1.0.17 partially
addressed (Safe Browsing as separate process, no offscreen pre-raster, etc.)
but the *real* cause was Bug #7.
**Hardening shipped in v1.0.17**:
- `WebSettingsCompat.setSafeBrowsingEnabled(false)` — Safe Browsing runs in
  a separate process that competes with the renderer for memory.
- `offscreenPreRaster = false`
- `allowFileAccess = false`, `allowContentAccess = false`,
  `allowFileAccessFromFileURLs = false`, `allowUniversalAccessFromFileURLs = false`
- Registered a `WebViewRenderProcessClient` that calls `renderer.terminate()`
  on `onRenderProcessUnresponsive` so a hung renderer triggers our clean
  recovery path before the OS reaps us violently.

### Bug #7 — `pauseTimers()` is process-wide, not per-WebView (v1.0.18) ⭐⭐⭐

**Symptom**: video freezes followed by renderer death.
**Root cause**: `WebView.pauseTimers()` pauses JavaScript timers, plugins,
and **media playback heartbeats across every WebView in the process** (Google's
docs are explicit). We were calling it on every tab switch and `onPause()`.
Result: a stale `pauseTimers()` from a previous tab activation, or the
activity briefly losing focus (notification, system gesture), would freeze
the active video's MSE buffer pump → decoder waits forever for data →
renderer becomes unresponsive → OS reaps it.
**Fix**: removed every `pauseTimers()` / `resumeTimers()` call. Use only
`onPause()` / `onResume()` which *are* per-WebView.
**Why desktop didn't have it**: desktop Chrome doesn't share a renderer
between tabs.

### Bug #8 — SwipeRefreshLayout forced software rendering (v1.0.18) ⭐

**Root cause**: SwipeRefreshLayout invalidates its child during the swipe
animation, which can cause the WebView to fall back to a software-rendered
layer. Software rendering of HD video → instant OOM.
**Fix**: explicit `setLayerType(View.LAYER_TYPE_HARDWARE, null)` on both
the WebView and the SwipeRefreshLayout host.

### Bug #9 — Pointless per-page JS injection (v1.0.18)

**Root cause**: We were running `evaluateJavascript("window.open=function(){return null}")`
on every `onPageFinished`. Redundant — popup blocking is already handled by
`setSupportMultipleWindows(false)` + `javaScriptCanOpenWindowsAutomatically=false`.
**Fix**: removed.

### Bug #10 — Fast tab interactions crashed the app (v1.0.19) ⭐

**Symptom**: rapid tab tapping / closing / URL submission crashed.
**Root causes (three races)**:
1. Tapping chip A then chip B before the first swap finished caused
   overlapping `removeView` / `addView` mid-layout → "specified child already
   has a parent" `IllegalStateException`.
2. `webView.destroy()` while the renderer was about to fire `onPageFinished`
   landed callbacks on a freed object.
3. Typing a new URL while the previous page was still loading left two loads
   racing in the same WebView.

**Fixes**:
- **Coalesced activation**: `activate()` posts a `Runnable` to the next
  frame; if you tap again before that frame, the earlier runnable is dropped.
- **`destroyWebViewSafely(wv)`**: `stopLoading()` → null `webChromeClient`
  → replace `webViewClient` with no-op → `loadUrl("about:blank")` →
  `clearHistory()` → detach → `destroy()` posted to next main-loop tick.
  Used by both `close()` and `rebuildAfterCrash()`.
- **`stopLoading()` before `loadUrl`** in `submitAddress`.
- Every view-system call wrapped in `runCatching`.

### Bug #11 — Fast video scrubbing crashed renderer (v1.0.21) ⭐⭐

**Symptom**: scrubbing a video timeline very fast crashed the renderer.
**Root cause**: HTML5 player writes `video.currentTime` 30+ times per second
during a scrub. Each write flushes Chromium's MediaSource buffer and triggers
an Android `MediaCodec` seek, which allocates a new GPU output surface and
discards the previous one. WebView shares its GPU thread with the renderer
(unlike desktop Chrome which has a separate GPU process). 30 surface
allocations in 2 seconds exhausts the renderer's GPU surface pool → GPU
thread chokes → renderer dies.
**Fix**: inject `assets/seek_throttle.js` at `onPageStarted` (BEFORE any
`<video>` element is constructed). It wraps `HTMLMediaElement.prototype.currentTime`'s
setter:
- First write: applied immediately.
- Writes within 120 ms window: stored as `__sbLast`, underlying setter NOT
  called.
- End of window: if `__sbLast` differs from current playhead by >0.05s,
  apply once.

A 30-seek burst becomes ~8 seeks. User still ends up at the exact frame
(latest value always honored). MediaCodec gets time to release each surface
before the next allocation.

**Why this is the only place to fix it**:
- No `WebViewClient` callback for "video seek".
- Android doesn't expose MediaCodec rate limits to apps.
- GPU surface pool size is set by the driver, not the app.
- Wrapping the JS setter is the only layer where we can rate-limit before
  the seek reaches the native decoder.

### Bug #12 — Renderer reaped by OS under memory pressure (v1.0.20) ⭐

**Root cause**: by default, Android marks WebView renderers as
`RENDERER_PRIORITY_WAIVED` whenever the activity isn't fully visible — even
briefly (notification shade, edge swipe). Under memory pressure, waived
renderers are killed first.
**Fix**: `wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)`.
Second arg `false` = don't waive when not visible. Renderer stays high
priority, OS kills other apps before ours.

### Bug #13 — One renderer, eight tabs, ~512 MB cap (v1.0.20) ⭐

**Critical fact**: every WebView in the app shares **one** renderer process
with a hard cap (~512 MB on most devices). 8 tabs = 8 DOMs + 8 JS heaps + 8
image caches in one process. One heavy video tab tips it over and *every*
tab dies.
**Fix**: tab hibernation. On `onTrimMemory(MODERATE+)`, every inactive tab
is hibernated:
- URL saved to `tab.hibernatedUrl`
- WebView parked on `about:blank`, history cleared
- Chip stays; tapping it triggers `loadUrl(savedUrl)` to wake it
The active tab effectively gets the whole renderer.

### Bug #14 — Cold-start vs. video decoding contention (v1.0.20)

**Root cause**: first WebView creation pays cold-start cost (fork renderer
process, init V8, warm GPU command buffer). If the first page is a video
site, the renderer is doing all that *while* MediaCodec is also trying to
allocate buffers → race → death.
**Fix**: pre-warm in `MainActivity.onCreate` before `setContentView`:
```kotlin
runCatching { android.webkit.WebView(applicationContext) }
```

### Bug #15 — bfcache held previous page in memory across origin changes (v1.0.28)

**Symptom**: A → B → back → C pattern caused renderer kill. Also any A → B
cross-origin nav left A's full DOM + JS heap alive in bfcache, eating
10–150 MB per prior page.
**Root cause**: `clearHistory()` was never called on navigation. `freeMemory()`
and `clearCache()` do not touch bfcache. With one shared renderer process
and a ~512 MB cap, holding 2–3 full pages simultaneously while loading a
third was enough to tip the renderer over.
**Fix**: `TabManager.resetForNavigation(tab, newUrl)` calls `clearHistory()`
(plus `stopLoading`, `clearCache(true)`, `clearFormData`, `freeMemory`) before
every cross-origin user-initiated navigation. Triggered at all three
user-initiated nav entry points: URL bar, bookmark tap, "open in same tab"
link dialog. NOT triggered on server-side redirects (`hasGesture() == false`)
to preserve OAuth redirect flows. NOT triggered when targeting `about:blank`
(hibernation). Back navigation across origins now reloads instead of
restoring from snapshot — `canGoBack()` returns false after a wipe, so the
existing dispatcher cascade closes the tab on back instead. Back within
the same origin is unaffected.
**Do not undo**: Do NOT remove the `clearHistory()` call from
`resetForNavigation`. It is the only call that actually drops bfcache.
`freeMemory()` alone does nothing for this problem.

---

## 4. Architectural constraints (the hard truths)

1. **WebView shares ONE renderer process across all tabs.** This is the
   single biggest difference from desktop Chrome and the source of most
   crashes. Architect around it (hibernation, careful memory mgmt).
2. **WebView shares its GPU thread with the renderer.** Desktop Chrome has a
   separate GPU process. Anything that pressures the GPU pressures the
   renderer too.
3. **`pauseTimers()` is process-wide.** Treat as poison.
4. **Native renderer crashes are invisible to JVM.** Must use
   `onRenderProcessGone()` to detect and recover.
5. **The renderer is `WAIVED` by default.** Always set
   `RENDERER_PRIORITY_IMPORTANT` with `waivedWhenNotVisible=false`.
6. **`enableOnBackInvokedCallback=true` makes `onKeyDown(KEYCODE_BACK)` not
   fire.** Use `OnBackPressedDispatcher`.
7. **WebView's only network hook is `shouldInterceptRequest`.** No
   `chrome.webRequest`. No header modification. This is why our ad blocker
   is far less capable than uBlock Origin.

---

## 5. Ad blocker / popup limitations vs. desktop

Desktop blockers (uBlock Origin) have:
- `chrome.webRequest` API — full request introspection
- ~150,000 curated ABP-syntax filter rules with auto-update
- Cosmetic filtering engine (CSS selector hides) + scriptlets
- Renderer-internal user-gesture tracking for popup blocking

We have:
- `WebViewClient.shouldInterceptRequest` — host/domain matching only
- Static `assets/blocklist.txt` with ~50 hosts
- `overlay_zapper.js` — one heuristic ("hide overlays") that sometimes
  false-positives
- `setSupportMultipleWindows(false)` + cross-origin nav prompt as a
  sledgehammer popup blocker

This is why mobile has the allow/block-list UI: we can't curate at uBlock
scale, so the user is the curator for sites we get wrong. The path to
"just works" parity is integrating Brave's `adblock-rust` (AAR available)
to get a real ABP filter engine.

---

## 6. Crash logging

- `MainActivity.installCrashLogger()` installs
  `Thread.setDefaultUncaughtExceptionHandler` that writes to
  `filesDir/last_crash.txt`.
- On next launch, `readAndClearLastCrash()` reads + deletes the file.
- If non-empty, shown in an `AlertDialog` with a Copy button.
- **Caveat**: this only catches JVM crashes. Native renderer deaths are
  caught by `onRenderProcessGone` and surfaced via Toast (see Bug #5).

---

## 7. Release process

```bash
cd /Users/avnish.kumar/Desktop/projects/web_browser
# 1. bump versionCode + versionName in android/app/build.gradle.kts
# 2. commit
git add android/
git commit -m "vX.Y.Z: short description"
git tag vX.Y.Z
git push && git push --tags
# 3. GitHub Actions builds debug APK, attaches to Release.
```

GitHub token (for downloading CI logs from macOS):
```bash
security find-internet-password -s github.com -w
```
No `gh` CLI on this machine — use raw `curl` with the keychain token.

---

## 8. Things NOT to "fix" without re-reading this file

- Do NOT add `pauseTimers()` / `resumeTimers()` back. (Bug #7)
- Do NOT remove `setLayerType(View.LAYER_TYPE_HARDWARE, ...)` from WebView
  or SwipeRefreshLayout. (Bug #8)
- Do NOT change `?android:attr/colorBackground` to `?attr/colorBackground`. (Bug #1)
- Do NOT remove the `runCatching` wrappers around view ops in `activate` /
  `close` / `rebuildAfterCrash`. (Bug #10)
- Do NOT change `Tab.webView` back to `val`. (Bug #5)
- Do NOT call `webView.destroy()` directly — always `destroyWebViewSafely`. (Bug #10)
- Do NOT remove `setRendererPriorityPolicy(IMPORTANT, false)`. (Bug #12)
- Do NOT remove `setSafeBrowsingEnabled(false)` without measuring memory
  impact first. (Bug #6)
- Do NOT remove the pre-warm `WebView(applicationContext)` in `onCreate`.
  (Bug #14)
- Do NOT inject `seek_throttle.js` in `onPageFinished` — must be
  `onPageStarted`, before the player constructs `<video>`. (Bug #11)
- Do NOT keep popup blocking JS injection on `onPageFinished` — it's
  redundant and was removed. (Bug #9)
- Do NOT remove `clearHistory()` from `resetForNavigation`. It is the only
  call that drops bfcache. (Bug #15)
- Do NOT call `resetForNavigation` inside `shouldOverrideUrlLoading` for
  redirects — only call it on user-initiated navigations. (Bug #15, OAuth flows)

---

## 9. Open ideas (not yet shipped)

- Integrate `adblock-rust` AAR for uBO-class blocking; remove allow/block UI
  from primary menu.
- Multi-process WebView via `WebViewCompat` + `WebViewProcessClient`
  isolation per tab (experimental — investigate cost).
- Foreground service for background audio/video playback so OS doesn't
  reap us mid-playback.
- Per-site default video quality injection (force 720p on YouTube/Twitch
  embeds) as belt-and-braces against future GPU OOMs.
