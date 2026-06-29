package com.example.hibari.ui

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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

private data class DownloadInfo(val url: String, val mimeType: String, val fileName: String)

@Composable
fun BrowserScreen(viewModel: BrowserViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val settings by viewModel.settingsState.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var pendingDownload by remember { mutableStateOf<DownloadInfo?>(null) }

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
                        webViewAction = null
                        viewModel.switchTab(id)
                    },
                    onTabClose = { viewModel.closeTab(it) },
                    onNewTab = { viewModel.createTab() },
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            key(uiState.activeTabId) {
                TabWebView(
                    tabId = uiState.activeTabId,
                    isPrivate = uiState.tabs.find { it.id == uiState.activeTabId }?.isPrivate ?: false,
                    initialUrl = uiState.currentUrl,
                    httpsOnly = settings.httpsOnly,
                    blockThirdPartyCookies = settings.blockThirdPartyCookies,
                    restoreState = { viewModel.consumeSavedState(uiState.activeTabId) },
                    onSaveState = { id, bundle -> viewModel.onWebViewSuspending(id, bundle) },
                    onPrivateTabClosed = { viewModel.clearPrivateTabData() },
                    onDownloadRequest = { url, mime, name ->
                        pendingDownload = DownloadInfo(url, mime, name)
                    },
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

    // Download confirmation dialog
    pendingDownload?.let { info ->
        val context = LocalContext.current
        DownloadConfirmDialog(
            fileName = info.fileName,
            onConfirm = {
                startDownload(context, info)
                pendingDownload = null
            },
            onDismiss = { pendingDownload = null },
        )
    }
}

// ── Tab WebView ──────────────────────────────────────────────────────────────

@Composable
private fun TabWebView(
    tabId: String,
    isPrivate: Boolean,
    initialUrl: String,
    httpsOnly: Boolean,
    blockThirdPartyCookies: Boolean,
    restoreState: () -> Bundle?,
    onSaveState: (String, Bundle) -> Unit,
    onPrivateTabClosed: () -> Unit,
    onDownloadRequest: (url: String, mimeType: String, fileName: String) -> Unit,
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
    val clientRef = remember { mutableStateOf<HibariWebViewClient?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Pause/resume WebView with the app lifecycle to stop background JS/animations
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> webViewRef.value?.onPause()
                Lifecycle.Event.ON_RESUME -> webViewRef.value?.onResume()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

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

    // Save WebView state when this composable leaves (tab switch / close).
    // For private tabs: wipe cookies, cache, and storage on disposal.
    DisposableEffect(tabId) {
        onDispose {
            webViewRef.value?.let { wv ->
                val bundle = Bundle()
                wv.saveState(bundle)
                onSaveState(tabId, bundle)
                if (isPrivate) onPrivateTabClosed()
            }
        }
    }

    AndroidView(
        factory = { context ->
            val client = HibariWebViewClient(
                onPageStarted = { url, _ -> onPageStarted(url) },
                onPageFinished = { url -> onPageFinished(url) },
                onNavigationStateChanged = onNavigationStateChanged,
                httpsOnly = httpsOnly,
            )
            clientRef.value = client
            WebViewFactory.create(context, client, blockThirdPartyCookies).also { wv ->
                wv.webChromeClient = buildChromeClient(
                    onProgressChanged = onProgressChanged,
                    onTitleReceived = onTitleReceived,
                )
                wv.setDownloadListener(buildDownloadListener(onDownloadRequest))
                webViewRef.value = wv
                val saved = restoreState()
                if (saved != null) wv.restoreState(saved)
                else if (initialUrl.isNotBlank()) wv.loadUrl(initialUrl)
            }
        },
        update = { wv ->
            webViewRef.value = wv
            clientRef.value?.updateHttpsOnly(httpsOnly)
            WebViewFactory.applyThirdPartyCookies(wv, blockThirdPartyCookies)
            onNavigationStateChanged(wv.canGoBack(), wv.canGoForward())
        },
        modifier = modifier,
    )
}

// ── WebChromeClient factory ───────────────────────────────────────────────────

/**
 * Builds a WebChromeClient that:
 *  - Forwards progress and title updates
 *  - DENIES all permission requests (camera, mic, etc.) — deny-by-default (DESIGN.md §7)
 *  - DENIES geolocation — deny-by-default
 */
private fun buildChromeClient(
    onProgressChanged: (Int) -> Unit,
    onTitleReceived: (String?) -> Unit,
): WebChromeClient = object : WebChromeClient() {

    override fun onProgressChanged(view: WebView?, newProgress: Int) =
        onProgressChanged(newProgress)

    override fun onReceivedTitle(view: WebView?, title: String?) =
        onTitleReceived(title)

    override fun onPermissionRequest(request: PermissionRequest) {
        request.deny()
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?,
    ) {
        callback?.invoke(origin, false, false)
    }
}

// ── DownloadListener factory ──────────────────────────────────────────────────

private fun buildDownloadListener(
    onDownloadRequest: (url: String, mimeType: String, fileName: String) -> Unit,
): DownloadListener = DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
    onDownloadRequest(url, mimeType ?: "*/*", fileName)
}

// ── Download helpers ─────────────────────────────────────────────────────────

private fun startDownload(context: Context, info: DownloadInfo) {
    val request = DownloadManager.Request(Uri.parse(info.url)).apply {
        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        setDestinationInExternalPublicDir(
            android.os.Environment.DIRECTORY_DOWNLOADS,
            info.fileName,
        )
        setMimeType(info.mimeType)
        addRequestHeader("User-Agent", android.webkit.WebSettings.getDefaultUserAgent(context))
    }
    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    dm.enqueue(request)
}

@Composable
private fun DownloadConfirmDialog(
    fileName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ダウンロード") },
        text = { Text("「$fileName」をダウンロードしますか？") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("ダウンロード") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
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
