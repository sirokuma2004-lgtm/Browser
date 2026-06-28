package com.example.hibari.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.URLEncoder

data class BrowserUiState(
    val currentUrl: String = "https://www.google.com",
    val displayUrl: String = "https://www.google.com",
    val pageTitle: String = "",
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val loadProgress: Int = 0,
)

class BrowserViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    fun onPageStarted(url: String?) {
        _uiState.update {
            it.copy(
                currentUrl = url ?: it.currentUrl,
                displayUrl = url ?: it.displayUrl,
                isLoading = true,
            )
        }
    }

    fun onPageFinished(url: String?) {
        _uiState.update {
            it.copy(
                currentUrl = url ?: it.currentUrl,
                displayUrl = url ?: it.displayUrl,
                isLoading = false,
                loadProgress = 100,
            )
        }
    }

    fun onProgressChanged(progress: Int) {
        _uiState.update { it.copy(loadProgress = progress, isLoading = progress < 100) }
    }

    fun onTitleReceived(title: String?) {
        if (!title.isNullOrBlank()) _uiState.update { it.copy(pageTitle = title) }
    }

    fun onNavigationStateChanged(canGoBack: Boolean, canGoForward: Boolean) {
        _uiState.update { it.copy(canGoBack = canGoBack, canGoForward = canGoForward) }
    }

    /**
     * Normalises user input and returns the URL that should be loaded.
     * Updates the UI state immediately so the address bar reflects the navigation.
     */
    fun navigate(input: String): String {
        val url = normalizeInput(input)
        _uiState.update { it.copy(currentUrl = url, displayUrl = url) }
        return url
    }

    private fun normalizeInput(input: String): String {
        val trimmed = input.trim()
        return when {
            // Already a valid absolute URL
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            // Looks like a hostname (contains a dot, no spaces, no slash-less path)
            trimmed.matches(Regex("^[\\w.-]+(\\.[\\w.-]+)+(:\\d+)?(/.*)?$")) -> "https://$trimmed"
            // Everything else → search
            else -> "https://www.google.com/search?q=${URLEncoder.encode(trimmed, "UTF-8")}"
        }
    }
}
