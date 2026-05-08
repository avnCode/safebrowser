// SafeBrowser - Electron main process
//
// Features:
// - Multi-tab UI (rendered in chrome; each tab is its own <webview>)
// - Strict / Lenient mode (persisted to userData/settings.json)
//     Strict  : block every page-initiated top-level nav / popup / redirect
//     Lenient : allow same-origin redirects automatically; cross-origin redirects
//               always require explicit user approval (toast prompt) — even when
//               the navigation started from a legitimate user click.
// - Link clicks are recognized via webview preload and pre-marked as user-initiated.
// - Cross-origin server redirects (302/303/etc) on top-level navs are intercepted
//   in onBeforeRequest and surfaced to the user.
// - Ad/tracker blocking via @cliqz/adblocker-electron (EasyList + EasyPrivacy).
// - HTML5 video fullscreen support.
// - Strips <meta refresh>, neutralizes window.open, suppresses beforeunload prompts.

const { app, BrowserWindow, ipcMain, session, shell, webContents } = require('electron');
const path = require('path');
const fs   = require('fs');
const fetch = require('cross-fetch');

// Stock-Chrome user agent. Must NOT contain the substring "Electron" or Google's
// sign-in flow rejects us with "This browser or app may not be secure."
const CHROME_UA =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';
// Apply at the command-line level so it's used from the very first request,
// before any session/webContents is created.
app.commandLine.appendSwitch('user-agent', CHROME_UA);
app.userAgentFallback = CHROME_UA;
const { ElectronBlocker } = require('@cliqz/adblocker-electron');

const HOME_URL  = 'file://' + path.join(__dirname, 'renderer', 'newtab.html');
const SEARCH_URL = (q) => 'https://www.google.com/search?q=' + encodeURIComponent(q);

// ---- Settings (persisted) --------------------------------------------------
let settings = { mode: 'strict', adblock: true };
let settingsPath;
function loadSettings() {
  settingsPath = path.join(app.getPath('userData'), 'settings.json');
  try { settings = { ...settings, ...JSON.parse(fs.readFileSync(settingsPath, 'utf8')) }; }
  catch (_) {}
}
function saveSettings() {
  try { fs.writeFileSync(settingsPath, JSON.stringify(settings, null, 2)); } catch (_) {}
}

// ---- Bookmarks (persisted) -------------------------------------------------
// Hidden by default — no toolbar bar; access via Cmd+B / star button menu.
let bookmarks = []; // [{ id, url, title, addedAt }]
let bookmarksPath;
function loadBookmarks() {
  bookmarksPath = path.join(app.getPath('userData'), 'bookmarks.json');
  try { bookmarks = JSON.parse(fs.readFileSync(bookmarksPath, 'utf8')) || []; }
  catch (_) { bookmarks = []; }
}
function saveBookmarks() {
  try { fs.writeFileSync(bookmarksPath, JSON.stringify(bookmarks, null, 2)); } catch (_) {}
}

// ---- Per-tab navigation state ---------------------------------------------
// userInitiatedNav : tab wcId -> Set<exact URLs>      (typed URL / clicked link / approved redirect)
// expectedOrigin   : tab wcId -> origin string         (what the user *intended* for the next mainFrame nav)
const userInitiatedNav = new Map();
const expectedOrigin   = new Map();
function originOf(url) { try { return new URL(url).origin; } catch (_) { return null; } }
function markUserNav(wcId, url) {
  if (!userInitiatedNav.has(wcId)) userInitiatedNav.set(wcId, new Set());
  userInitiatedNav.get(wcId).add(url);
  const o = originOf(url); if (o) expectedOrigin.set(wcId, o);
}
function consumeUserNav(wcId, url) {
  const set = userInitiatedNav.get(wcId);
  if (set && set.has(url)) { set.delete(url); return true; }
  return false;
}

// Pending cross-origin confirmations: token -> { wcId, url }
const pendingConfirms = new Map();
let confirmCounter = 1;

// ---- Window + ad blocker setup ---------------------------------------------
let mainWindow;
let adblockEngine = null;
let adblockSession = null;

// Built-in allow-list: hostnames the ad blocker should NEVER touch. These
// sites often break under aggressive cosmetic/script filtering. Edit freely.
const ADBLOCK_ALLOWLIST = [
  'youtube.com', 'youtu.be',
  'google.com', 'gmail.com',
  'drive.google.com', 'docs.google.com', 'maps.google.com',
];

// Ad blocker: cache filter lists on disk so subsequent starts are instant.
async function setupAdBlocker(ses) {
  adblockSession = ses;
  try {
    const cachePath = path.join(app.getPath('userData'), 'adblock-engine.bin');
    adblockEngine = await ElectronBlocker.fromPrebuiltAdsAndTracking(fetch, {
      path: cachePath,
      read: async (p) => fs.promises.readFile(p),
      write: async (p, buf) => fs.promises.writeFile(p, buf),
    });
    // Add EasyList-format exception filters for our allow-list.
    // `@@||host^$document` whitelists the whole document and all subresources.
    try {
      const lines = ADBLOCK_ALLOWLIST.flatMap(h => [
        `@@||${h}^$document`,
        `@@||${h}^`,
      ]);
      adblockEngine.updateFromDiff({ added: lines });
      console.log('[adblock] allow-list applied:', ADBLOCK_ALLOWLIST.join(', '));
    } catch (e) {
      console.log('[adblock] allow-list error:', e.message);
    }

    if (settings.adblock) {
      adblockEngine.enableBlockingInSession(ses);
      console.log('[adblock] enabled');
    } else {
      console.log('[adblock] loaded but OFF (per settings)');
    }
    return adblockEngine;
  } catch (e) {
    console.log('[adblock] disabled (error):', e.message);
    return null;
  }
}

function setAdblockEnabled(on) {
  if (!adblockEngine || !adblockSession) return;
  try {
    if (on) adblockEngine.enableBlockingInSession(adblockSession);
    else    adblockEngine.disableBlockingInSession(adblockSession);
    console.log('[adblock]', on ? 'enabled' : 'disabled');
  } catch (e) { console.log('[adblock toggle error]', e.message); }
}

function applyHardening(ses) {
  // Permissions allow-listed for ANY site (these are individually user-gestured
  // or harmless and breaking them ruins normal browsing).
  const ALWAYS_ALLOW = new Set(['fullscreen', 'pointerLock']);

  // Trusted-domain allow-list: certain origins additionally get clipboard,
  // media, idle-detection, etc. auto-granted. Edit freely.
  const TRUSTED_HOST_SUFFIXES = [
    'google.com', 'gmail.com', 'youtube.com', 'youtu.be',
    'maps.google.com', 'drive.google.com', 'docs.google.com',
    'github.com', 'githubusercontent.com',
    'wikipedia.org', 'duckduckgo.com',
    'open.spotify.com', 'spotify.com',
  ];
  const TRUSTED_PERMISSIONS = new Set([
    'clipboard-read', 'clipboard-sanitized-write',
    'media', 'idle-detection',
  ]);
  function isTrusted(url) {
    try {
      const h = new URL(url).hostname.toLowerCase();
      return TRUSTED_HOST_SUFFIXES.some(s => h === s || h.endsWith('.' + s));
    } catch (_) { return false; }
  }

  ses.setPermissionRequestHandler((wc, permission, callback, details) => {
    const url = details?.requestingUrl || wc?.getURL() || '';
    if (ALWAYS_ALLOW.has(permission)) {
      console.log('[perm:allow]', permission, url);
      return callback(true);
    }
    if (TRUSTED_PERMISSIONS.has(permission) && isTrusted(url)) {
      console.log('[perm:allow-trusted]', permission, url);
      return callback(true);
    }
    console.log('[perm:deny]', permission, url);
    callback(false);
  });
  ses.setPermissionCheckHandler((_wc, permission, requestingOrigin) => {
    if (ALWAYS_ALLOW.has(permission)) return true;
    return TRUSTED_PERMISSIONS.has(permission) && isTrusted(requestingOrigin);
  });
}

// Build a "redirect attempt" callback. Used by per-webContents `will-redirect`
// (server 30x) events. Cancels and prompts if cross-origin to user's intent.
function shouldPromptRedirect(wc, nextUrl) {
  if (!wc || wc.isDestroyed()) return null;
  const wcId = wc.id;
  if (consumeUserNav(wcId, nextUrl)) return null;

  let curUrl = '';
  try { curUrl = wc.getURL(); } catch (_) { return null; }
  if (curUrl.startsWith('file://') && curUrl.endsWith('newtab.html')) return null;

  const expected = expectedOrigin.get(wcId);
  const reqOrigin = originOf(nextUrl);
  if (!expected) return null;
  if (reqOrigin && reqOrigin === expected) return null;
  if (reqOrigin && sameRegistrableDomain(expected, reqOrigin)) {
    expectedOrigin.set(wcId, reqOrigin);
    return null;
  }
  return { expected };
}

// Compare by registered domain (last 2 labels) so www.google.com == google.com.
function sameRegistrableDomain(a, b) {
  try {
    const ha = new URL(a).hostname.toLowerCase().split('.');
    const hb = new URL(b).hostname.toLowerCase().split('.');
    return ha.slice(-2).join('.') === hb.slice(-2).join('.');
  } catch (_) { return false; }
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280, height: 820, title: 'SafeBrowser',
    webPreferences: {
      preload: path.join(__dirname, 'preload-chrome.js'),
      contextIsolation: true, sandbox: true, webviewTag: true,
    },
  });
  mainWindow.loadFile(path.join(__dirname, 'renderer', 'index.html'));
}

function attachWebviewPolicy(wc) {
  // Each webview gets its own webContents UA; session-level UA isn't always
  // inherited for navigator.userAgent in the renderer.
  try { wc.setUserAgent(CHROME_UA); } catch (_) {}

  wc.setWindowOpenHandler(({ url }) => {
    mainWindow?.webContents.send('popup-blocked', { url, fromWcId: wc.id });
    return { action: 'deny' };
  });

  wc.on('will-prevent-unload', (event) => event.preventDefault());

  // Server-side redirect (30x) on a top-level navigation. Cancel and prompt
  // when the new URL is cross-origin to what the user intended.
  wc.on('will-redirect', (event, nextUrl) => {
    try {
      if (wc.isDestroyed()) return;
      const verdict = shouldPromptRedirect(wc, nextUrl);
      if (!verdict) return;
      event.preventDefault();
      const token = String(confirmCounter++);
      pendingConfirms.set(token, { wcId: wc.id, url: nextUrl });
      console.log('[redirect-prompt]', 'expected', verdict.expected, '→ got', nextUrl);
      mainWindow?.webContents.send('redirect-confirm', {
        token, fromWcId: wc.id, expected: verdict.expected, url: nextUrl,
      });
    } catch (err) {
      console.log('[will-redirect:guard]', err.message);
    }
  });

  // will-navigate handles JS/SPA navigation that doesn't go through the
  // network layer (e.g. history.pushState followed by location.assign).
  wc.on('will-navigate', (event, nextUrl) => {
    try {
      if (wc.isDestroyed()) return;
      if (consumeUserNav(wc.id, nextUrl)) return;

      let curUrl = '';
      try { curUrl = wc.getURL(); } catch (_) { return; }
      if (curUrl.startsWith('file://') && curUrl.endsWith('newtab.html')) return;

      let curU, nextU;
      try { curU = new URL(curUrl); nextU = new URL(nextUrl); } catch (_) {}

      if (curU && nextU) {
        if (curU.origin === nextU.origin && curU.pathname === nextU.pathname) return;
        if (settings.mode === 'lenient' && curU.origin === nextU.origin) return;
      }

      console.log('[block:will-navigate]', nextUrl, '(mode=' + settings.mode + ')');
      event.preventDefault();
      mainWindow?.webContents.send('redirect-blocked', { url: nextUrl, fromWcId: wc.id });
    } catch (err) {
      console.log('[will-navigate:guard]', err.message);
    }
  });

  wc.on('dom-ready', () => {
    if (wc.isDestroyed()) return;
    wc.executeJavaScript(`
      (() => {
        document.querySelectorAll('meta[http-equiv="refresh" i]').forEach(m => m.remove());
        try { window.open = () => null; } catch(_) {}
      })();
    `).catch(() => {});
  });

  // Diagnostic: log any failed load so blank pages aren't silent.
  wc.on('did-fail-load', (_e, errorCode, errorDescription, validatedURL, isMainFrame) => {
    if (!isMainFrame) return;
    if (errorCode === -3) return; // ABORTED (e.g. our own cancel)
    console.log('[did-fail-load]', errorCode, errorDescription, validatedURL);
  });
  wc.on('did-finish-load', () => {
    try { if (!wc.isDestroyed()) console.log('[did-finish-load]', wc.getURL()); } catch (_) {}
  });

  wc.on('enter-html-full-screen', () => {
    mainWindow?.setFullScreen(true);
    mainWindow?.webContents.send('webview-fullscreen', { wcId: wc.id, fullscreen: true });
  });
  wc.on('leave-html-full-screen', () => {
    mainWindow?.setFullScreen(false);
    mainWindow?.webContents.send('webview-fullscreen', { wcId: wc.id, fullscreen: false });
  });

  // Clean up state when a webview goes away.
  wc.on('destroyed', () => {
    userInitiatedNav.delete(wc.id);
    expectedOrigin.delete(wc.id);
  });
}

app.whenReady().then(async () => {
  // Last-resort net so transient frame-disposal races don't show the
  // "A JavaScript error occurred in the main process" dialog.
  process.on('uncaughtException', (err) => {
    const msg = err && err.message ? err.message : String(err);
    if (/Render frame was disposed|WebFrameMain/.test(msg)) {
      console.log('[uncaught:ignored]', msg);
      return;
    }
    console.error('[uncaught]', err);
  });

  loadSettings();
  loadBookmarks();

  const ses = session.fromPartition('persist:safebrowser');
  // Mimic stock Chrome on macOS so bot-detection (Cloudflare, hCaptcha, Google
  // sign-in, etc.) doesn't flag us as "Electron". This is the single biggest
  // cause of CAPTCHAs, 5xx errors, and "browser may not be secure" rejections.
  ses.setUserAgent(CHROME_UA);

  // Scrub Electron from request headers — including the Sec-CH-UA client
  // hints, which Google's account flow inspects in addition to User-Agent.
  const SEC_CH_UA = '"Chromium";v="124", "Google Chrome";v="124", "Not-A.Brand";v="99"';
  ses.webRequest.onBeforeSendHeaders((details, cb) => {
    const h = details.requestHeaders;
    h['User-Agent'] = CHROME_UA;
    if ('sec-ch-ua' in h || 'Sec-CH-UA' in h) h['sec-ch-ua'] = SEC_CH_UA;
    if ('sec-ch-ua-platform' in h || 'Sec-CH-UA-Platform' in h) h['sec-ch-ua-platform'] = '"macOS"';
    if ('sec-ch-ua-mobile' in h || 'Sec-CH-UA-Mobile' in h) h['sec-ch-ua-mobile'] = '?0';
    cb({ requestHeaders: h });
  });

  applyHardening(ses);
  // Non-blocking: window opens immediately; ad blocker hooks in once filter
  // lists are loaded (cached after first run).
  setupAdBlocker(ses);

  app.on('web-contents-created', (_e, wc) => {
    if (wc.getType() === 'webview') attachWebviewPolicy(wc);
  });

  // ---- IPC -----------------------------------------------------------------
  ipcMain.handle('normalize-url', (_e, raw) => {
    raw = String(raw || '').trim();
    if (!raw) return HOME_URL;
    if (/^(https?|file):\/\//i.test(raw)) return raw;
    const looksLikeUrl = !/\s/.test(raw) && /^[\w.-]+\.[a-z]{2,}([\/?#].*)?$/i.test(raw);
    return looksLikeUrl ? 'https://' + raw : SEARCH_URL(raw);
  });

  // Address-bar / Home / programmatic load.
  ipcMain.handle('mark-user-nav', (_e, { wcId, url }) => {
    markUserNav(wcId, url);
    return true;
  });

  // Link click captured by the webview preload.
  ipcMain.on('webview-link-click', (e, url) => {
    markUserNav(e.sender.id, url);
  });

  // User answered a cross-origin redirect prompt.
  ipcMain.handle('confirm-redirect', (_e, { token, allow }) => {
    const pending = pendingConfirms.get(token);
    if (!pending) return false;
    pendingConfirms.delete(token);
    if (!allow) return false;
    // Approve and reload that URL on the originating webview.
    const wc = webContents.fromId(pending.wcId);
    if (!wc) return false;
    markUserNav(pending.wcId, pending.url);
    wc.loadURL(pending.url);
    return true;
  });

  ipcMain.handle('get-home',     () => HOME_URL);
  ipcMain.handle('get-webview-preload-url', () =>
    'file://' + path.join(__dirname, 'preload-webview.js'));
  ipcMain.handle('get-settings', () => settings);
  ipcMain.handle('set-settings', (_e, partial) => {
    const prev = settings;
    settings = { ...settings, ...(partial || {}) };
    saveSettings();
    if (partial && 'adblock' in partial && partial.adblock !== prev.adblock) {
      setAdblockEnabled(!!settings.adblock);
    }
    BrowserWindow.getAllWindows().forEach(w =>
      w.webContents.send('settings-changed', settings));
    for (const wc of webContents.getAllWebContents()) {
      if (wc.getType() === 'webview') wc.send('settings-changed', settings);
    }
    return settings;
  });

  ipcMain.handle('open-external', (_e, url) => shell.openExternal(url));

  // ---- Bookmarks IPC ------------------------------------------------------
  ipcMain.handle('bookmarks:list', () => bookmarks);
  ipcMain.handle('bookmarks:add', (_e, { url, title }) => {
    if (!url) return bookmarks;
    // No duplicates by URL.
    if (bookmarks.some(b => b.url === url)) return bookmarks;
    bookmarks.unshift({
      id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
      url, title: title || url, addedAt: Date.now(),
    });
    saveBookmarks();
    return bookmarks;
  });
  ipcMain.handle('bookmarks:remove', (_e, id) => {
    bookmarks = bookmarks.filter(b => b.id !== id);
    saveBookmarks();
    return bookmarks;
  });
  ipcMain.handle('bookmarks:has', (_e, url) => bookmarks.some(b => b.url === url));

  createWindow();
});

app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
