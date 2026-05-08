# SafeBrowser for Android

A native Android browser built with Kotlin + Jetpack Compose + WebView. Same
philosophy as the desktop version: aggressive ad/tracker blocking, no
silent cross-origin redirects, no popups, fully your control.

## Features

- **Multi-tab browsing** with a tab-switcher sheet (badge shows count).
- **Strict / Lenient redirect modes** — Strict prompts you before any
  cross-origin navigation that wasn't a direct user gesture.
- **Cross-origin redirect prompt** — Allow once / Block.
- **Ad & tracker blocker** — host-based blocklist (50+ trackers bundled,
  enriched in the background from the StevenBlack hosts file). Toggle in
  one tap from the toolbar.
- **Per-host allow-list** — disable blocking on sites that need it
  (e.g. videos that won't play). YouTube, Google, Gmail, Drive, Docs,
  Maps are pre-allowed.
- **Bookmarks** — hidden behind a button; tap ☆ to save the current page,
  open the panel to revisit. Persisted in app-private storage.
- **Wallpaper new-tab page** with Google search built in.
- **Dark mode** — System / Light / Dark.
- **Pinch-zoom**, swipe-back via system gesture, system-back navigates
  WebView history first then closes the tab.
- **Chrome 124 user-agent spoof** (Android variant) to avoid bot-detection
  CAPTCHAs.
- **HTTPS-only** (`usesCleartextTraffic=false`) and **third-party cookies
  blocked** by default.
- Opens links from other apps (registered as a browser).

## Project layout

```
android/
├── settings.gradle.kts        Project config
├── build.gradle.kts           Root build
├── gradle.properties
├── gradle/wrapper/            Wrapper config (jar generated on first build)
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/
        │   ├── newtab.html        New-tab wallpaper page
        │   └── blocklist.txt      Bundled starter blocklist
        ├── res/                   Theme, icons, colors, backup rules
        └── java/com/safebrowser/app/
            ├── MainActivity.kt        Activity + theme wrapper
            ├── BrowserScreen.kt       Compose UI (toolbar, sheets, dialog)
            ├── WebViewHost.kt         Per-tab WebView + WebViewClient
            ├── BrowserViewModel.kt    Tabs, bookmarks, redirect prompt
            ├── AdBlocker.kt           Host-based blocker + allow-list
            ├── Bookmarks.kt           JSON-on-disk bookmarks store
            ├── Settings.kt            DataStore-backed settings
            └── UrlNormalizer.kt       URL parsing helpers
```

---

## Installation (no Play Store, just sideload)

You only need this **once** on your Mac. Then any APK builds onto your
phone in seconds.

### 1 — Install Android Studio (~1.2 GB, free)

Download "Android Studio" from <https://developer.android.com/studio>
(Apple Silicon `.dmg` if you're on an M-series Mac, Intel otherwise).

Open the `.dmg` → drag **Android Studio** to Applications → launch it.

On first launch the setup wizard will:
- accept the Android SDK license,
- download Android SDK Platform 34,
- download build-tools and the platform-tools (which contains `adb`).

Just click **Next → Standard → Finish** and let it download (~1.5 GB).

### 2 — Open this project

In Android Studio: **File → Open…** and pick
`/Users/avnish.kumar/Desktop/projects/web_browser/android/`.

The first sync will download Gradle 8.7 and a few hundred MB of
dependencies. **Let it finish.** When you see *"BUILD SUCCESSFUL"* in the
bottom panel, you're ready.

> Tip: if Android Studio complains *"Gradle wrapper jar missing"*, click
> **"Use Gradle from: gradle-wrapper.properties"** in the prompt — it
> will fetch the jar automatically.

### 3 — Enable USB debugging on your phone

On your Android phone:

1. **Settings → About phone** → tap **Build number** 7 times. (You'll see
   *"You are now a developer."*)
2. **Settings → System → Developer options** → turn **USB debugging** ON.
3. Plug the phone into your Mac with a USB-C cable. The phone will pop a
   dialog asking to *"Allow USB debugging from this computer"* — tap
   **Allow** (and **Always allow** if you'd rather not see it again).

### 4 — Install & run

In Android Studio's top toolbar, your phone should now appear in the
device dropdown (next to the green ▶ button). Click ▶ (**Run 'app'**).

Android Studio will:
- compile the app,
- generate `app-debug.apk`,
- push it to your phone over USB,
- launch it.

That's it — you're using SafeBrowser on your phone.

### 5 — (Optional) Install the APK on other phones

After building once, the APK lives at:

```
android/app/build/outputs/apk/debug/app-debug.apk
```

To install it on a different Android phone:

1. Copy `app-debug.apk` to the phone (AirDrop equivalent / Drive / email).
2. On the phone, tap the file. Android will warn *"For your security, your
   phone is not allowed to install unknown apps from this source."*
3. Tap **Settings** → enable *"Allow from this source"* → tap **Install**.

> The debug APK is signed with Android Studio's auto-generated debug key.
> That's fine for personal sideload but **don't publish it**. For
> distribution build a release with your own keystore.

### 6 — (Optional) Build from the command line

If you'd rather skip Android Studio's UI:

```bash
cd /Users/avnish.kumar/Desktop/projects/web_browser/android

# First-time only: generate the gradle wrapper jar.
# (Requires either system 'gradle' OR Android Studio to have synced once.)
./gradlew wrapper   # (if gradlew exists from a previous sync)

# Build a debug APK
./gradlew assembleDebug

# Install onto the connected phone (adb on PATH)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If `gradlew` doesn't exist yet, run **Build → Build APK** in Android Studio
once — this generates the wrapper script + jar.

---

## Updating the ad blocklist

The first time the app runs (and on every cold start while online) it
fetches a fresh hosts file from
<https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts> and
caches it in app-private storage. No internet on first run? It falls back
to the bundled `assets/blocklist.txt` (~50 entries).

To force a refresh: **Settings → Storage → SafeBrowser → Clear cache.**

---

## Troubleshooting

| Symptom                            | Fix                                                                 |
|-----------------------------------|---------------------------------------------------------------------|
| Phone doesn't appear in Studio    | Re-plug USB cable, switch USB mode to *"File transfer"*, accept the *Allow USB debugging* dialog. |
| YouTube ads show through          | The blocker is host-based, so it can't strip in-stream video ads. Toggle Ads OFF if a video stalls. |
| A site won't load at all          | Open Settings → tap *"Allow <host>"* on that page, or flip Mode to **Lenient**. |
| Build fails: "minSdk 26"          | Your phone is on Android < 8.0 — bump `minSdk` down in `app/build.gradle.kts` if you really need it. |
| White screen after install        | Open Settings → Apps → SafeBrowser → Storage → **Clear data** to reset. |
