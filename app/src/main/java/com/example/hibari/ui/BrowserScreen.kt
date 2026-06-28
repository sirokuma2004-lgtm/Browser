package com.example.hibari.ui

import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.hibari.browser.HibariWebViewClient
import com.example.hibari.browser.TabInfo
import com.example.hibari.browser.WebViewFactory
import com.example.hibari.data.HistoryEntity

// Represents a one-shot command sent to the active WebView.
private sealed class WebViewAction {
    data class LoadUrl(val url: String) : WebViewAction()
    object Back : WebViewAction()
    object Forward : WebViewAction()
    object Reload : WebViewAction()
    object Stop : WebViewAction()
}

@Composable
fun BrowserScreen(viewModel: BrowserViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(viewModel = viewModel, onBack = { showSettings = false })
        return
    }

    // Signal channel: BrowserScreen → TabWebView (cleared after consumption)
    var webViewAction by remember { mutableStateOf<WebViewAction?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
            Column {
                AddressBar(
                    uiState = uiState,
                    onNavigate = { input ->
                        val url = viewModel.navigate(input)
                        webViewAction = WebViewAction.LoadUrl(url)
                    },
                    onBack = { webViewAction = WebViewAction.Back },
                    onForward = { webViewAction = WebViewAction.Forward },
                    onRefreshOrStop = {
                        webViewAction = if (uiState.isLoading) WebViewAction.Stop
                        else WebViewAction.Reload
                    },
                    onToggleBookmark = { viewModel.toggleBookmark() },
                    onSuggestionClick = { suggestion ->
                        val url = viewModel.navigate(suggestion.url)
                        webViewAction = WebViewAction.LoadUrl(url)
                    },
                    onAddressTextChanged = { viewModel.onAddressBarTextChanged(it) },
                    onAddressFocusLost = { viewModel.clearSuggestions() },
                    onSettings = { showSettings = true },
                )
                if (uiState.isLoading && uiState.loadProgress < 100) {
                    LinearProgressIndicator(
                        progress = { uiState.loadProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TabBar(
                    tabs = uiState.tabs,
                    activeTabId = uiState.activeTabId,
                    onTabClick = { id ->
                        // Switching tab clears the pending action for the old tab
                        webViewAction = null
                        viewModel.switchTab(id)
                    },
                    onTabClose = { viewModel.closeTab(it) },
                    onNewTab = { viewModel.createTab() },
                )
            }
        }

        // Re-create the WebView composable whenever the active tab changes.
        // DisposableEffect inside TabWebView saves the state before destruction.
        Box(modifier = Modifier.fillMaxSize()) {
            key(uiState.activeTabId) {
                TabWebView(
                    tabId = uiState.activeTabId,
                    initialUrl = uiState.currentUrl,
                    restoreState = { viewModel.consumeSavedState(uiState.activeTabId) },
                    onSaveState = { id, bundle -> viewModel.onWebViewSuspending(id, bundle) },
                    action = webViewAction,
                    onActionConsumed = { webViewAction = null },
                    onPageStarted = { url -> viewModel.onPageStarted(url) },
                    onPageFinished = { url -> viewModel.onPageFinished(url) },
                    onProgressChanged = { p -> viewModel.onProgressChanged(p) },
                    onTitleReceived = { t -> viewModel.onTitleReceived(t) },
                    onNavigationStateChanged = { b, f -> viewModel.onNavigationStateChanged(b, f) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

// ── Tab WebView ──────────────────────────────────────────────────────────────

@Composable
private fun TabWebView(
    tabId: String,
    initialUrl: String,
    restoreState: () -> Bundle?,
    onSaveState: (String, Bundle) -> Unit,
    action: WebViewAction?,
    onActionConsumed: () -> Unit,
    onPageStarted: (String?) -> Unit,
    onPageFinished: (String?) -> Unit,
    onProgressChanged: (Int) -> Unit,
    onTitleReceived: (String?) -> Unit,
    onNavigationStateChanged: (Boolean, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    // Execute one-shot WebView commands
    LaunchedEffect(action) {
        if (action == null) return@LaunchedEffect
        val wv = webViewRef.value ?: return@LaunchedEffect
        when (action) {
            is WebViewAction.LoadUrl -> wv.loadUrl(action.url)
            WebViewAction.Back -> if (wv.canGoBack()) wv.goBack()
            WebViewAction.Forward -> if (wv.canGoForward()) wv.goForward()
            WebViewAction.Reload -> wv.reload()
            WebViewAction.Stop -> wv.stopLoading()
        }
        onActionConsumed()
    }

    // Save WebView state when this composable leaves (tab switch / close)
    DisposableEffect(tabId) {
        onDispose {
            webViewRef.value?.let { wv ->
                val bundle = Bundle()
                wv.saveState(bundle)
                onSaveState(tabId, bundle)
            }
        }
    }

    AndroidView(
        factory = { context ->
            val client = HibariWebViewClient(
                onPageStarted = { url, _ -> onPageStarted(url) },
                onPageFinished = { url -> onPageFinished(url) },
                onNavigationStateChanged = onNavigationStateChanged,
            )
            WebViewFactory.create(context, client).also { wv ->
                wv.webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) =
                        onProgressChanged(newProgress)
                    override fun onReceivedTitle(view: WebView?, title: String?) =
                        onTitleReceived(title)
                }
                webViewRef.value = wv
                val saved = restoreState()
                if (saved != null) wv.restoreState(saved)
                else if (initialUrl.isNotBlank()) wv.loadUrl(initialUrl)
            }
        },
        update = { wv ->
            webViewRef.value = wv
            onNavigationStateChanged(wv.canGoBack(), wv.canGoForward())
        },
        modifier = modifier,
    )
}

// ── Address Bar ──────────────────────────────────────────────────────────────

@Composable
private fun AddressBar(
    uiState: BrowserUiState,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefreshOrStop: () -> Unit,
    onToggleBookmark: () -> Unit,
    onSuggestionClick: (HistoryEntity) -> Unit,
    onAddressTextChanged: (String) -> Unit,
    onAddressFocusLost: () -> Unit,
    onSettings: () -> Unit,
) {
    var addressText by remember { mutableStateOf(uiState.displayUrl) }
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.displayUrl) {
        if (!isFocused) addressText = uiState.displayUrl
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, enabled = uiState.canGoBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
            }
            IconButton(onClick = onForward, enabled = uiState.canGoForward) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "進む")
            }
            OutlinedTextField(
                value = addressText,
                onValueChange = { text ->
                    addressText = text
                    onAddressTextChanged(text)
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 2.dp)
                    .onFocusChanged { fs ->
                        isFocused = fs.isFocused
                        if (!fs.isFocused) onAddressFocusLost()
                    },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { onNavigate(addressText) }),
                placeholder = { Text("URLまたは検索", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodySmall,
            )
            IconButton(onClick = onRefreshOrStop) {
                Icon(
                    if (uiState.isLoading) Icons.Filled.Close else Icons.Filled.Refresh,
                    contentDescription = if (uiState.isLoading) "停止" else "更新",
                )
            }
            IconButton(onClick = onToggleBookmark) {
                Icon(
                    if (uiState.isCurrentPageBookmarked) Icons.Filled.Bookmark
                    else Icons.Filled.BookmarkBorder,
                    contentDescription = "ブックマーク",
                )
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "設定")
            }
        }

        // History suggestions dropdown
        if (uiState.suggestions.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                LazyColumn {
                    items(uiState.suggestions) { suggestion ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSuggestionClick(suggestion) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = suggestion.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = suggestion.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

// ── Tab Bar ──────────────────────────────────────────────────────────────────

@Composable
private fun TabBar(
    tabs: List<TabInfo>,
    activeTabId: String,
    onTabClick: (String) -> Unit,
    onTabClose: (String) -> Unit,
    onNewTab: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            TabChip(
                tab = tab,
                isActive = tab.id == activeTabId,
                onClick = { onTabClick(tab.id) },
                onClose = { onTabClose(tab.id) },
            )
        }
        IconButton(onClick = onNewTab, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "新しいタブ", modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.size(4.dp))
    }
}

@Composable
private fun TabChip(
    tab: TabInfo,
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val bg = if (isActive) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant

    Row(
        modifier = Modifier
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .background(bg, shape = MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = tab.title.ifBlank { tab.url },
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 100.dp),
        )
        IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "閉じる", modifier = Modifier.size(12.dp))
        }
    }
}
