// Preload attached to every <webview>.
//
// 1. Exposes a tiny safe API (settings get/set) — used only by our new-tab page.
// 2. Captures user link-clicks and forwards them to main so legitimate
//    clicks aren't treated as page-initiated navigation.
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('safe', Object.freeze({
  getSettings: () => ipcRenderer.invoke('get-settings'),
  setSettings: (partial) => ipcRenderer.invoke('set-settings', partial),
}));

// Capture-phase click listener: fires before the page's own handlers,
// so a malicious page can't easily suppress it. We forward URLs of
// normal left/middle-click activations on <a href>.
function reportClick(e) {
  if (e.button !== 0 && e.button !== 1) return;
  let el = e.target;
  while (el && el.nodeType === 1 && el.tagName !== 'A') el = el.parentNode;
  if (!el || el.tagName !== 'A') return;
  const href = el.href;
  if (!href || !/^https?:\/\//i.test(href)) return;
  ipcRenderer.send('webview-link-click', href);
}
window.addEventListener('click',    reportClick, true);
window.addEventListener('auxclick', reportClick, true);
