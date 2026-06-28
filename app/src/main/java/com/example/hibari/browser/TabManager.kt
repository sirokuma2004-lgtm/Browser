package com.example.hibari.browser

import android.os.Bundle
import java.util.UUID

/** Immutable per-tab data surfaced to the UI. */
data class TabInfo(
    val id: String = UUID.randomUUID().toString(),
    val url: String = "https://www.google.com",
    val title: String = "新しいタブ",
    val isLoading: Boolean = false,
    val isPrivate: Boolean = false,
)

/**
 * Manages the list of open tabs and their saved WebView states.
 *
 * WebView instances themselves live in the Compose tree (AndroidView).
 * When a tab is suspended (deactivated), the caller saves the WebView's
 * Bundle here via [saveWebViewState]. On restore, [consumeSavedState]
 * returns that Bundle so the new WebView can call restoreState().
 *
 * This class is owned by BrowserViewModel (survives rotation).
 */
class TabManager {

    private val _tabs = mutableListOf<TabInfo>()
    val tabs: List<TabInfo> get() = _tabs.toList()

    // Saved WebView states keyed by tab ID (consumed once on restore)
    private val savedStates = mutableMapOf<String, Bundle>()

    var activeTabId: String = ""
        private set

    init {
        val initial = TabInfo()
        _tabs.add(initial)
        activeTabId = initial.id
    }

    fun createTab(url: String = "https://www.google.com", isPrivate: Boolean = false): TabInfo {
        val tab = TabInfo(url = url, isPrivate = isPrivate)
        _tabs.add(tab)
        return tab
    }

    fun closeTab(id: String): String? {
        val idx = _tabs.indexOfFirst { it.id == id }
        if (idx < 0) return null
        _tabs.removeAt(idx)
        savedStates.remove(id)
        if (_tabs.isEmpty()) {
            val newTab = TabInfo()
            _tabs.add(newTab)
            activeTabId = newTab.id
            return newTab.id
        }
        if (activeTabId == id) {
            activeTabId = _tabs.getOrNull(idx)?.id ?: _tabs.last().id
        }
        return activeTabId
    }

    fun switchTo(id: String) {
        if (_tabs.any { it.id == id }) activeTabId = id
    }

    fun updateTab(id: String, url: String? = null, title: String? = null, isLoading: Boolean? = null) {
        val idx = _tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        _tabs[idx] = _tabs[idx].copy(
            url = url ?: _tabs[idx].url,
            title = title ?: _tabs[idx].title,
            isLoading = isLoading ?: _tabs[idx].isLoading,
        )
    }

    fun saveWebViewState(tabId: String, bundle: Bundle) {
        savedStates[tabId] = bundle
    }

    /** Returns and removes the saved state (it's consumed on restore). */
    fun consumeSavedState(tabId: String): Bundle? = savedStates.remove(tabId)

    fun activeTab(): TabInfo? = _tabs.find { it.id == activeTabId }
}
