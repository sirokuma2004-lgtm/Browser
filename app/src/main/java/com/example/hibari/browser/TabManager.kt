package com.example.hibari.browser

import android.content.Context
import android.os.Bundle
import android.webkit.WebView

/**
 * M1: Full tab management — creation, switching, suspend/resume.
 *
 * Suspend strategy (per DESIGN.md §5.2 / §8):
 *  Non-active tabs save their state (URL, scroll, WebView.saveState) and destroy
 *  the WebView instance to free memory. On re-activation a new WebView is
 *  created via WebViewFactory and the state is restored.
 *
 * This stub exposes the data types that M1 will flesh out so that M0 can compile.
 */

data class TabState(
    val id: String,
    val url: String,
    val title: String = "",
    val savedState: Bundle? = null,
    val isPrivate: Boolean = false,
)

class TabManager(private val context: Context) {

    private val _tabs = mutableListOf<TabState>()
    private val liveWebViews = mutableMapOf<String, WebView>()

    val tabs: List<TabState> get() = _tabs.toList()

    fun createTab(url: String, isPrivate: Boolean = false): TabState {
        val tab = TabState(id = java.util.UUID.randomUUID().toString(), url = url, isPrivate = isPrivate)
        _tabs.add(tab)
        return tab
    }

    /** Returns the live WebView for [tabId], creating one if necessary. */
    fun getOrCreateWebView(
        tabId: String,
        client: HibariWebViewClient,
    ): WebView {
        return liveWebViews.getOrPut(tabId) {
            WebViewFactory.create(context, client).also { wv ->
                _tabs.find { it.id == tabId }?.savedState?.let { wv.restoreState(it) }
            }
        }
    }

    /** Suspend a tab: save state and release the WebView. */
    fun suspend(tabId: String) {
        val wv = liveWebViews.remove(tabId) ?: return
        val bundle = Bundle()
        wv.saveState(bundle)
        val idx = _tabs.indexOfFirst { it.id == tabId }
        if (idx >= 0) {
            _tabs[idx] = _tabs[idx].copy(
                url = wv.url ?: _tabs[idx].url,
                title = wv.title ?: _tabs[idx].title,
                savedState = bundle,
            )
        }
        wv.destroy()
    }

    fun closeTab(tabId: String) {
        suspend(tabId)
        _tabs.removeAll { it.id == tabId }
    }
}
