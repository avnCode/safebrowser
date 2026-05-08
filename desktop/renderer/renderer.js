// Chrome UI: tabs + address bar + mode toggle.
const tabsEl    = document.getElementById('tabs');
const newtabBtn = document.getElementById('newtab-btn');
const viewsEl   = document.getElementById('views');
const addr      = document.getElementById('addr');
const toastEl   = document.getElementById('toast');
const modePill  = document.getElementById('mode-pill');
const adPill    = document.getElementById('ad-pill');

let HOME = 'about:blank';
let WEBVIEW_PRELOAD = '';
let settings = { mode: 'strict' };
const tabs = [];          // [{ id, webview, tabEl, title, url }]
let activeId = null;
let nextId = 1;

// ---- Toast -----------------------------------------------------------------
let toastTimer = null;
function toast(msg, url) {
  toastEl.innerHTML = msg + (url ? ` <a id="toast-open">Open manually</a>` : '');
  toastEl.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toastEl.classList.remove('show'), 5000);
  if (url) {
    document.getElementById('toast-open').onclick = (e) => {
      e.preventDefault();
      navigateActive(url);
    };
  }
}

// ---- Tabs ------------------------------------------------------------------
function activeTab() { return tabs.find(t => t.id === activeId); }

function renderTabBar() {
  // Remove old tab elements (keep + button).
  [...tabsEl.querySelectorAll('.tab')].forEach(el => el.remove());
  for (const t of tabs) {
    const el = document.createElement('div');
    el.className = 'tab' + (t.id === activeId ? ' active' : '');
    el.innerHTML = `<span class="title"></span><span class="close">×</span>`;
    el.querySelector('.title').textContent = t.title || 'New Tab';
    el.onclick = (e) => {
      if (e.target.classList.contains('close')) { closeTab(t.id); return; }
      activate(t.id);
    };
    tabsEl.insertBefore(el, newtabBtn);
    t.tabEl = el;
  }
}

function activate(id) {
  activeId = id;
  for (const t of tabs) t.webview.classList.toggle('active', t.id === id);
  const t = activeTab();
  if (t) addr.value = t.url || '';
  renderTabBar();
  refreshStar();
}

function createTab(url) {
  const id = nextId++;
  const wv = document.createElement('webview');
  wv.setAttribute('partition', 'persist:safebrowser');
  wv.setAttribute('webpreferences', 'contextIsolation=yes,sandbox=yes');
  // Tiny preload that only exposes get/set settings — used by the new-tab page.
  if (WEBVIEW_PRELOAD) wv.setAttribute('preload', WEBVIEW_PRELOAD);
  wv.setAttribute('src', 'about:blank');
  // Required for HTML5 video fullscreen inside <webview>.
  wv.setAttribute('allowfullscreen', '');
  viewsEl.appendChild(wv);

  const tab = { id, webview: wv, tabEl: null, title: 'New Tab', url: '' };
  tabs.push(tab);

  wv.addEventListener('did-start-loading', () => { tab.title = 'Loading…'; renderTabBar(); });
  wv.addEventListener('page-title-updated', (e) => { tab.title = e.title; renderTabBar(); });
  wv.addEventListener('did-navigate', (e) => {
    tab.url = e.url; if (tab.id === activeId) { addr.value = e.url; refreshStar(); }
  });
  wv.addEventListener('did-navigate-in-page', (e) => {
    tab.url = e.url; if (tab.id === activeId) { addr.value = e.url; refreshStar(); }
  });

  // HTML5 video fullscreen: hide the chrome and let the webview cover the window.
  // Main process also toggles the OS window fullscreen on these events.
  wv.addEventListener('enter-html-full-screen', () => {
    document.body.classList.add('fullscreen');
  });
  wv.addEventListener('leave-html-full-screen', () => {
    document.body.classList.remove('fullscreen');
  });

  // When the webContents attaches we can mark URLs / load home.
  wv.addEventListener('dom-ready', () => {
    if (!wv._booted) {
      wv._booted = true;
      navigateTab(tab, url || HOME);
    }
  }, { once: false });

  activate(id);
  return tab;
}

function closeTab(id) {
  const idx = tabs.findIndex(t => t.id === id);
  if (idx < 0) return;
  const [t] = tabs.splice(idx, 1);
  t.webview.remove();
  if (tabs.length === 0) {
    createTab(HOME);
  } else if (activeId === id) {
    activate(tabs[Math.max(0, idx - 1)].id);
  } else {
    renderTabBar();
  }
}

// ---- Navigation ------------------------------------------------------------
async function navigateTab(tab, raw) {
  const finalUrl = await window.safe.normalizeUrl(raw);
  const wcId = tab.webview.getWebContentsId();
  await window.safe.markUserNav(wcId, finalUrl);
  tab.webview.loadURL(finalUrl);
  tab.url = finalUrl;
  if (tab.id === activeId) addr.value = finalUrl;
}
function navigateActive(raw) {
  const t = activeTab();
  if (!t) return;
  navigateTab(t, raw);
}

// ---- Wire UI ---------------------------------------------------------------
document.getElementById('go').onclick     = () => navigateActive(addr.value.trim());
addr.addEventListener('keydown', (e) => { if (e.key === 'Enter') navigateActive(addr.value.trim()); });
document.getElementById('back').onclick   = () => { const t=activeTab(); if (t && t.webview.canGoBack()) t.webview.goBack(); };
document.getElementById('fwd').onclick    = () => { const t=activeTab(); if (t && t.webview.canGoForward()) t.webview.goForward(); };
document.getElementById('reload').onclick = () => { const t=activeTab(); if (t) t.webview.reload(); };
document.getElementById('home').onclick   = () => navigateActive(HOME);
newtabBtn.onclick = () => createTab(HOME);

// Keyboard shortcuts: Cmd/Ctrl+T new tab, Cmd/Ctrl+W close tab, Cmd/Ctrl+L focus address bar.
window.addEventListener('keydown', (e) => {
  const mod = e.metaKey || e.ctrlKey;
  if (mod && e.key === 't') { e.preventDefault(); createTab(HOME); }
  else if (mod && e.key === 'w') { e.preventDefault(); if (activeId) closeTab(activeId); }
  else if (mod && e.key === 'l') { e.preventDefault(); addr.focus(); addr.select(); }
  else if (mod && e.key === 'd') { e.preventDefault(); toggleBookmarkActive(); }
  else if (mod && e.key === 'b') { e.preventDefault(); toggleBookmarksPanel(); }
});

// ---- Bookmarks -----------------------------------------------------------
const starBtn       = document.getElementById('star');
const bmPanel       = document.getElementById('bookmarks-panel');
const bmListEl      = document.getElementById('bookmarks-list');
const bmEmptyEl     = document.getElementById('bookmarks-empty');
const bmBtn         = document.getElementById('bookmarks-btn');
const bmCloseBtn    = document.getElementById('bookmarks-close');

async function refreshStar() {
  const t = activeTab();
  if (!t || !t.url || /^file:\/\//i.test(t.url) || /^about:/i.test(t.url)) {
    starBtn.textContent = '☆';
    starBtn.classList.remove('saved');
    return;
  }
  const has = await window.safe.bookmarksHas(t.url);
  starBtn.textContent = has ? '★' : '☆';
  starBtn.classList.toggle('saved', has);
}

async function toggleBookmarkActive() {
  const t = activeTab();
  if (!t || !t.url) return;
  if (/^file:\/\//i.test(t.url) || /^about:/i.test(t.url)) {
    toast('Cannot bookmark this page.');
    return;
  }
  const has = await window.safe.bookmarksHas(t.url);
  if (has) {
    const list = await window.safe.bookmarksList();
    const item = list.find(b => b.url === t.url);
    if (item) await window.safe.bookmarksRemove(item.id);
    toast('Bookmark removed.');
  } else {
    await window.safe.bookmarksAdd(t.url, t.title);
    toast('Bookmark saved.');
  }
  refreshStar();
  if (bmPanel.classList.contains('show')) renderBookmarks();
}
starBtn.onclick = toggleBookmarkActive;

async function renderBookmarks() {
  const list = await window.safe.bookmarksList();
  bmListEl.innerHTML = '';
  bmEmptyEl.style.display = list.length ? 'none' : 'block';
  for (const b of list) {
    const row = document.createElement('div');
    row.className = 'bm-item';
    let host = '';
    try { host = new URL(b.url).hostname; } catch (_) {}
    row.innerHTML = `<span class="bm-title"></span><span class="bm-host"></span><span class="bm-del" title="Remove">×</span>`;
    row.querySelector('.bm-title').textContent = b.title || b.url;
    row.querySelector('.bm-host').textContent  = host;
    row.onclick = (e) => {
      if (e.target.classList.contains('bm-del')) {
        window.safe.bookmarksRemove(b.id).then(renderBookmarks).then(refreshStar);
        return;
      }
      navigateActive(b.url);
      bmPanel.classList.remove('show');
    };
    bmListEl.appendChild(row);
  }
}

async function toggleBookmarksPanel() {
  const showing = bmPanel.classList.toggle('show');
  if (showing) await renderBookmarks();
}
bmBtn.onclick = toggleBookmarksPanel;
bmCloseBtn.onclick = () => bmPanel.classList.remove('show');
// Close panel when clicking outside.
document.addEventListener('mousedown', (e) => {
  if (!bmPanel.classList.contains('show')) return;
  if (bmPanel.contains(e.target) || bmBtn.contains(e.target)) return;
  bmPanel.classList.remove('show');
});

// Mode pill toggle.
function refreshModePill() {
  modePill.textContent = settings.mode === 'lenient' ? 'Lenient' : 'Strict';
  modePill.classList.toggle('lenient', settings.mode === 'lenient');
}
modePill.onclick = async () => {
  settings = await window.safe.setSettings({ mode: settings.mode === 'strict' ? 'lenient' : 'strict' });
  refreshModePill();
  toast('Mode set to ' + settings.mode + '.');
};

// Ad-blocker pill toggle.
function refreshAdPill() {
  const on = settings.adblock !== false;
  adPill.textContent = 'Ads: ' + (on ? 'ON' : 'OFF');
  adPill.classList.toggle('off', !on);
}
adPill.onclick = async () => {
  settings = await window.safe.setSettings({ adblock: !(settings.adblock !== false) });
  refreshAdPill();
  toast('Ad blocker ' + (settings.adblock ? 'enabled' : 'disabled') + '. Reloading…');
  const t = activeTab(); if (t) t.webview.reload();
};
window.safe.onSettingsChanged((s) => { settings = s; refreshModePill(); refreshAdPill(); });

// HTML5 video fullscreen: main forwards events here so we hide chrome reliably
// (the <webview> DOM element doesn't always emit enter/leave-html-full-screen).
window.safe.onWebviewFullscreen(({ fullscreen }) => {
  document.body.classList.toggle('fullscreen', !!fullscreen);
});

// Blocked-action notifications.
window.safe.onPopupBlocked(({ url }) => toast('Popup blocked.', url));
window.safe.onRedirectBlocked(({ url }) => toast('Redirect blocked.', url));

// Cross-origin redirect confirmation prompt.
const confirmEl = document.getElementById('confirm');
const cfExpected = document.getElementById('cf-expected');
const cfUrl      = document.getElementById('cf-url');
const cfAllow    = document.getElementById('cf-allow');
const cfBlock    = document.getElementById('cf-block');
let pendingToken = null;
window.safe.onRedirectConfirm(({ token, expected, url }) => {
  pendingToken = token;
  cfExpected.textContent = expected || '(unknown)';
  cfUrl.textContent = url;
  confirmEl.classList.add('show');
});
function closeConfirm(allow) {
  if (!pendingToken) return;
  window.safe.confirmRedirect(pendingToken, allow);
  pendingToken = null;
  confirmEl.classList.remove('show');
}
cfAllow.onclick = () => closeConfirm(true);
cfBlock.onclick = () => closeConfirm(false);

// Boot.
(async () => {
  HOME = await window.safe.getHome();
  WEBVIEW_PRELOAD = await window.safe.getWebviewPreloadUrl();
  settings = await window.safe.getSettings();
  refreshModePill();
  refreshAdPill();
  createTab(HOME);
})();
