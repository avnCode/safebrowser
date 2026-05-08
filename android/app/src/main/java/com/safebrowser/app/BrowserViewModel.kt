package com.safebrowser.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val HOME_URL = "file:///android_asset/newtab.html"

data class TabState(
    val id: Long,
    val url: String,
    val title: String = "New Tab",
    val isLoading: Boolean = false,
    /** What origin the user *intended* this tab to be on (for cross-origin prompts). */
    val expectedOrigin: String? = null,
)

data class RedirectPrompt(
    val tabId: Long,
    val expected: String,
    val nextUrl: String,
)

class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    val adBlocker = AdBlocker(app.applicationContext)
    val bookmarksStore = BookmarksStore(app.applicationContext)

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _tabs = MutableStateFlow<List<TabState>>(emptyList())
    val tabs: StateFlow<List<TabState>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow(0L)
    val activeTabId: StateFlow<Long> = _activeTabId.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    private val _redirectPrompt = MutableStateFlow<RedirectPrompt?>(null)
    val redirectPrompt: StateFlow<RedirectPrompt?> = _redirectPrompt.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** Per-tab "go back in WebView history" action, registered by WebViewHost. */
    private val backHandlers = mutableMapOf<Long, () -> Boolean>()

    fun registerBack(tabId: Long, handler: () -> Boolean) { backHandlers[tabId] = handler }
    fun unregisterBack(tabId: Long) { backHandlers.remove(tabId) }
    /** Returns true if the WebView consumed the back press. */
    fun goBackInActiveTab(): Boolean = backHandlers[_activeTabId.value]?.invoke() ?: false

    private var nextId = 1L

    init {
        viewModelScope.launch {
            adBlocker.load()
            _bookmarks.value = bookmarksStore.list()
            SettingsStore.flow(getApplication()).collect { _settings.value = it }
        }
        if (_tabs.value.isEmpty()) newTab(HOME_URL, activate = true)
    }

    // ---- Tabs ----------------------------------------------------------

    fun newTab(url: String = HOME_URL, activate: Boolean = true) {
        val id = nextId++
        val tab = TabState(id = id, url = url, expectedOrigin = UrlNormalizer.origin(url))
        _tabs.update { it + tab }
        if (activate) _activeTabId.value = id
    }

    fun closeTab(id: Long) {
        _tabs.update { it.filterNot { t -> t.id == id } }
        if (_tabs.value.isEmpty()) newTab(HOME_URL, true)
        else if (_activeTabId.value == id) _activeTabId.value = _tabs.value.last().id
    }

    fun activate(id: Long) { _activeTabId.value = id }

    fun activeTab(): TabState? = _tabs.value.firstOrNull { it.id == _activeTabId.value }

    fun updateActiveTab(transform: (TabState) -> TabState) {
        _tabs.update { list -> list.map { if (it.id == _activeTabId.value) transform(it) else it } }
    }

    /** Called when user types in the address bar and submits. */
    fun navigateActive(rawInput: String) {
        val url = UrlNormalizer.normalize(rawInput)
        updateActiveTab { it.copy(url = url, expectedOrigin = UrlNormalizer.origin(url) ?: it.expectedOrigin) }
    }

    /** Called when user taps a bookmark or new-tab tile. */
    fun loadInActive(url: String) = updateActiveTab {
        it.copy(url = url, expectedOrigin = UrlNormalizer.origin(url) ?: it.expectedOrigin)
    }

    fun setTabUrl(tabId: Long, url: String) {
        _tabs.update { list -> list.map { if (it.id == tabId) it.copy(url = url) else it } }
    }

    fun setTabTitle(tabId: Long, title: String) {
        _tabs.update { list -> list.map { if (it.id == tabId) it.copy(title = title) else it } }
    }

    fun setTabLoading(tabId: Long, loading: Boolean) {
        _tabs.update { list -> list.map { if (it.id == tabId) it.copy(isLoading = loading) else it } }
    }

    /**
     * Decide whether a navigation should be intercepted with a prompt.
     * Returns true if the WebView should LOAD the URL (allowed). False to block.
     * If a prompt is shown, returns false and sets _redirectPrompt; the user's
     * choice will resume via [confirmRedirect].
     */
    fun shouldAllowNavigation(tab: TabState, nextUrl: String, isUserGesture: Boolean): Boolean {
        // Always allow internal home page
        if (nextUrl.startsWith("file://") || nextUrl.startsWith("about:")) return true

        val mode = _settings.value.mode
        val expected = tab.expectedOrigin
        val nextOrigin = UrlNormalizer.origin(nextUrl)

        // No expectation yet (fresh tab) → adopt and allow.
        if (expected == null) {
            updateActiveTab { it.copy(expectedOrigin = nextOrigin ?: it.expectedOrigin) }
            return true
        }

        // Same origin → always allow.
        if (nextOrigin != null && nextOrigin == expected) return true

        // Same registrable domain (e.g. www.x.com ↔ x.com) → adopt + allow.
        if (UrlNormalizer.sameRegistrableDomain(
                UrlNormalizer.host(expected), UrlNormalizer.host(nextUrl)
            )) {
            updateActiveTab { it.copy(expectedOrigin = nextOrigin ?: it.expectedOrigin) }
            return true
        }

        // Cross-origin with a real user click → allow + adopt new origin.
        if (isUserGesture) {
            updateActiveTab { it.copy(expectedOrigin = nextOrigin ?: it.expectedOrigin) }
            return true
        }

        // Lenient: silently allow programmatic cross-origin nav.
        if (mode == Mode.Lenient) {
            updateActiveTab { it.copy(expectedOrigin = nextOrigin ?: it.expectedOrigin) }
            return true
        }

        // Strict: prompt the user.
        _redirectPrompt.value = RedirectPrompt(
            tabId = tab.id, expected = expected, nextUrl = nextUrl
        )
        return false
    }

    fun confirmRedirect(allow: Boolean) {
        val p = _redirectPrompt.value ?: return
        _redirectPrompt.value = null
        if (allow) {
            _tabs.update { list ->
                list.map {
                    if (it.id == p.tabId) it.copy(
                        url = p.nextUrl,
                        expectedOrigin = UrlNormalizer.origin(p.nextUrl) ?: it.expectedOrigin,
                    ) else it
                }
            }
        }
    }

    // ---- Bookmarks -----------------------------------------------------

    fun toggleBookmarkActive() = viewModelScope.launch {
        val t = activeTab() ?: return@launch
        val url = t.url
        if (url.startsWith("file://") || url.startsWith("about:")) {
            _toast.value = "Cannot bookmark this page"; return@launch
        }
        if (bookmarksStore.has(url)) {
            val item = bookmarksStore.list().firstOrNull { it.url == url }
            if (item != null) _bookmarks.value = bookmarksStore.remove(item.id)
            _toast.value = "Bookmark removed"
        } else {
            _bookmarks.value = bookmarksStore.add(url, t.title)
            _toast.value = "Bookmark saved"
        }
    }

    fun deleteBookmark(id: String) = viewModelScope.launch {
        _bookmarks.value = bookmarksStore.remove(id)
    }

    fun isActiveBookmarked(): Boolean {
        val u = activeTab()?.url ?: return false
        return _bookmarks.value.any { it.url == u }
    }

    fun consumeToast(): String? {
        val t = _toast.value
        _toast.value = null
        return t
    }

    // ---- Settings ------------------------------------------------------

    fun setMode(mode: Mode) = viewModelScope.launch {
        SettingsStore.set(getApplication()) { it.copy(mode = mode) }
    }

    fun setAdblock(enabled: Boolean) = viewModelScope.launch {
        SettingsStore.set(getApplication()) { it.copy(adblock = enabled) }
    }

    fun setTheme(t: ThemeChoice) = viewModelScope.launch {
        SettingsStore.set(getApplication()) { it.copy(theme = t) }
    }

    fun toggleHostAllow(host: String) = viewModelScope.launch {
        if (adBlocker.isAllowed(host)) adBlocker.disallow(host) else adBlocker.allow(host)
        // Trigger a reflow by toggling settings (no-op transform)
        SettingsStore.set(getApplication()) { it }
    }
}
