package com.example.hibari.ui

import android.app.Application
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hibari.bookmarks.BookmarkRepository
import com.example.hibari.browser.TabInfo
import com.example.hibari.browser.TabManager
import com.example.hibari.data.AppDatabase
import com.example.hibari.data.BrowserPreferences
import com.example.hibari.data.HistoryEntity
import com.example.hibari.history.HistoryRepository
import com.example.hibari.net.DohResolver
import com.example.hibari.net.ProxyManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLEncoder

// ── UI state types ────────────────────────────────────────────────────────────

data class BrowserUiState(
    val tabs: List<TabInfo> = emptyList(),
    val activeTabId: String = "",
    val currentUrl: String = "",
    val displayUrl: String = "",
    val pageTitle: String = "",
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val loadProgress: Int = 0,
    val suggestions: List<HistoryEntity> = emptyList(),
    val isCurrentPageBookmarked: Boolean = false,
)

data class SettingsUiState(
    val secureDnsEnabled: Boolean = false,
    val dohProviderUrl: String = DohResolver.Preset.CLOUDFLARE.url,
    val httpsOnly: Boolean = true,
    val blockThirdPartyCookies: Boolean = true,
    val proxyOverrideSupported: Boolean = false,
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    val historyRepo = HistoryRepository(db.historyDao())
    val bookmarkRepo = BookmarkRepository(db.bookmarkDao())
    val tabManager = TabManager()
    val proxyManager = ProxyManager(viewModelScope)
    private val prefs = BrowserPreferences(application)

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    val settingsState: StateFlow<SettingsUiState> = combine(
        prefs.secureDnsEnabled,
        prefs.dohProviderUrl,
        prefs.httpsOnly,
        prefs.blockThirdPartyCookies,
    ) { values ->
        SettingsUiState(
            secureDnsEnabled = values[0] as Boolean,
            dohProviderUrl = values[1] as String,
            httpsOnly = values[2] as Boolean,
            blockThirdPartyCookies = values[3] as Boolean,
            proxyOverrideSupported = proxyManager.isProxyOverrideSupported,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    private var suggestionJob: Job? = null
    private var proxyPort: Int = -1

    init {
        syncTabsToState()
        observeSecureDnsSettings()
    }

    // ── Secure DNS (M2) ──────────────────────────────────────────────────────

    private fun observeSecureDnsSettings() {
        viewModelScope.launch {
            prefs.secureDnsEnabled.collect { enabled ->
                if (enabled) {
                    startProxy(prefs.dohProviderUrl.value)
                } else {
                    proxyManager.stop()
                    proxyPort = -1
                }
            }
        }
    }

    private fun startProxy(dohUrl: String) {
        viewModelScope.launch {
            val port = proxyManager.start(dohUrl)
            if (port > 0) {
                proxyPort = port
                proxyManager.applyToWebView(port) {}
            }
        }
    }

    // ── Settings actions ─────────────────────────────────────────────────────

    fun setSecureDnsEnabled(enabled: Boolean) = prefs.setSecureDnsEnabled(enabled)

    fun setDohProvider(url: String) {
        prefs.setDohProviderUrl(url)
        if (proxyPort > 0) {
            viewModelScope.launch { proxyManager.updateDohProvider(url) }
        }
    }

    fun setHttpsOnly(enabled: Boolean) = prefs.setHttpsOnly(enabled)

    fun setBlockThirdPartyCookies(enabled: Boolean) = prefs.setBlockThirdPartyCookies(enabled)

    fun clearBrowsingData() {
        viewModelScope.launch {
            historyRepo.deleteAll()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
    }

    fun clearPrivateTabData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }

    // ── Tab management ───────────────────────────────────────────────────────

    fun createTab(url: String = "https://www.google.com", isPrivate: Boolean = false) {
        val tab = tabManager.createTab(url, isPrivate)
        tabManager.switchTo(tab.id)
        syncTabsToState()
    }

    fun closeTab(id: String) {
        tabManager.closeTab(id)
        syncTabsToState()
    }

    fun switchTab(id: String) {
        tabManager.switchTo(id)
        syncTabsToState()
    }

    fun onWebViewSuspending(tabId: String, state: Bundle) {
        tabManager.saveWebViewState(tabId, state)
    }

    fun consumeSavedState(tabId: String): Bundle? = tabManager.consumeSavedState(tabId)

    // ── Page lifecycle ───────────────────────────────────────────────────────

    fun onPageStarted(url: String?) {
        tabManager.updateTab(tabManager.activeTabId, url = url, isLoading = true)
        _uiState.update {
            it.copy(
                currentUrl = url ?: it.currentUrl,
                displayUrl = url ?: it.displayUrl,
                isLoading = true,
                isCurrentPageBookmarked = false,
            )
        }
        syncTabsToState()
    }

    fun onPageFinished(url: String?) {
        tabManager.updateTab(tabManager.activeTabId, url = url, isLoading = false)
        _uiState.update {
            it.copy(
                currentUrl = url ?: it.currentUrl,
                displayUrl = url ?: it.displayUrl,
                isLoading = false,
                loadProgress = 100,
            )
        }
        syncTabsToState()
        val tab = tabManager.activeTab()
        if (tab != null && !tab.isPrivate && !url.isNullOrBlank()) {
            viewModelScope.launch {
                historyRepo.record(url, _uiState.value.pageTitle)
                checkBookmarkState(url)
            }
        }
    }

    fun onProgressChanged(progress: Int) {
        _uiState.update { it.copy(loadProgress = progress, isLoading = progress < 100) }
    }

    fun onTitleReceived(title: String?) {
        if (!title.isNullOrBlank()) {
            tabManager.updateTab(tabManager.activeTabId, title = title)
            _uiState.update { it.copy(pageTitle = title) }
            syncTabsToState()
        }
    }

    fun onNavigationStateChanged(canGoBack: Boolean, canGoForward: Boolean) {
        _uiState.update { it.copy(canGoBack = canGoBack, canGoForward = canGoForward) }
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    fun navigate(input: String): String {
        val url = normalizeInput(input)
        tabManager.updateTab(tabManager.activeTabId, url = url)
        _uiState.update { it.copy(currentUrl = url, displayUrl = url, suggestions = emptyList()) }
        syncTabsToState()
        return url
    }

    // ── Address bar suggestions ──────────────────────────────────────────────

    fun onAddressBarTextChanged(query: String) {
        suggestionJob?.cancel()
        if (query.length < 2) {
            _uiState.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestionJob = viewModelScope.launch {
            delay(200)
            val results = historyRepo.search(query)
            _uiState.update { it.copy(suggestions = results) }
        }
    }

    fun clearSuggestions() {
        _uiState.update { it.copy(suggestions = emptyList()) }
    }

    // ── Bookmarks ────────────────────────────────────────────────────────────

    fun toggleBookmark() {
        val url = _uiState.value.currentUrl
        val title = _uiState.value.pageTitle
        if (url.isBlank()) return
        viewModelScope.launch {
            if (_uiState.value.isCurrentPageBookmarked) {
                val root = bookmarkRepo.getRootBookmarks()
                root.filter { it.url == url }.forEach { bookmarkRepo.delete(it.id) }
                _uiState.update { it.copy(isCurrentPageBookmarked = false) }
            } else {
                bookmarkRepo.add(url, title)
                _uiState.update { it.copy(isCurrentPageBookmarked = true) }
            }
        }
    }

    private fun checkBookmarkState(url: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCurrentPageBookmarked = bookmarkRepo.isBookmarked(url)) }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { proxyManager.stop() }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun syncTabsToState() {
        val activeTab = tabManager.activeTab()
        _uiState.update { state ->
            state.copy(
                tabs = tabManager.tabs,
                activeTabId = tabManager.activeTabId,
                currentUrl = activeTab?.url ?: state.currentUrl,
                displayUrl = activeTab?.url ?: state.displayUrl,
                pageTitle = activeTab?.title ?: state.pageTitle,
            )
        }
    }

    private fun normalizeInput(input: String): String {
        val s = input.trim()
        return when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.matches(Regex("^[\\w.-]+(\\.[\\w.-]+)+(:\\d+)?(/.*)?$")) -> "https://$s"
            else -> "https://www.google.com/search?q=${URLEncoder.encode(s, "UTF-8")}"
        }
    }
}
