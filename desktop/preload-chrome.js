// Preload for the chrome (address bar / tabs UI / new-tab page).
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('safe', {
  getWebviewPreloadUrl: () => ipcRenderer.invoke('get-webview-preload-url'),
  normalizeUrl: (raw) => ipcRenderer.invoke('normalize-url', raw),
  markUserNav:  (wcId, url) => ipcRenderer.invoke('mark-user-nav', { wcId, url }),
  getHome:      () => ipcRenderer.invoke('get-home'),
  getSettings:  () => ipcRenderer.invoke('get-settings'),
  setSettings:  (partial) => ipcRenderer.invoke('set-settings', partial),
  openExternal: (url) => ipcRenderer.invoke('open-external', url),

  // Bookmarks
  bookmarksList:   () => ipcRenderer.invoke('bookmarks:list'),
  bookmarksAdd:    (url, title) => ipcRenderer.invoke('bookmarks:add', { url, title }),
  bookmarksRemove: (id) => ipcRenderer.invoke('bookmarks:remove', id),
  bookmarksHas:    (url) => ipcRenderer.invoke('bookmarks:has', url),

  onPopupBlocked:    (cb) => ipcRenderer.on('popup-blocked',    (_e, p) => cb(p)),
  onRedirectBlocked: (cb) => ipcRenderer.on('redirect-blocked', (_e, p) => cb(p)),
  onRedirectConfirm: (cb) => ipcRenderer.on('redirect-confirm', (_e, p) => cb(p)),
  confirmRedirect:   (token, allow) => ipcRenderer.invoke('confirm-redirect', { token, allow }),
  onSettingsChanged: (cb) => ipcRenderer.on('settings-changed', (_e, s) => cb(s)),
  onWebviewFullscreen: (cb) => ipcRenderer.on('webview-fullscreen', (_e, p) => cb(p)),
});
