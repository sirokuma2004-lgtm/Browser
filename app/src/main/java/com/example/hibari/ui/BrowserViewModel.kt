package com.example.hibari.ui

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hibari.bookmarks.BookmarkRepository
import com.example.hibari.browser.TabInfo
import com.example.hibari.browser.TabManager
import com.example.hibari.data.AppDatabase
import com.example.hibari.data.HistoryEntity
import com.example.hibari.history.HistoryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLEncoder

data class BrowserUiState(
    // Tabs
    val tabs: List<TabInfo> = emptyList(),
    val activeTabId: String = "",
    // Active tab browsing state
    val currentUrl: String = "",
    val displayUrl: String = "",
    val pageTitle: String = "",
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val loadProgress: Int = 0,
    // Address bar suggestions
    val suggestions: List<HistoryEntity> = emptyList(),
    // Bookmark state for current page
    val isCurrentPageBookmarked: Boolean = false,
)

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    val historyRepo = HistoryRepository(db.historyDao())
    val bookmarkRepo = BookmarkRepository(db.bookmarkDao())
    val tabManager = TabManager()

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private var suggestionJob: Job? = null

    init {
        syncTabsToState()
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

    /** Called by Compose when a WebView is about to be destroyed (tab switch / close). */
    fun onWebViewSuspending(tabId: String, state: Bundle) {
        tabManager.saveWebViewState(tabId, state)
    }

    /** Returns saved WebView state for a tab (consumed once). */
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
        // Record history (skip private tabs)
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
                // Remove — find by URL and delete (simplified: delete all with this URL)
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
            val bookmarked = bookmarkRepo.isBookmarked(url)
            _uiState.update { it.copy(isCurrentPageBookmarked = bookmarked) }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun syncTabsToState() {
        val activeId = tabManager.activeTabId
        val activeTab = tabManager.activeTab()
        _uiState.update { state ->
            state.copy(
                tabs = tabManager.tabs,
                activeTabId = activeId,
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
