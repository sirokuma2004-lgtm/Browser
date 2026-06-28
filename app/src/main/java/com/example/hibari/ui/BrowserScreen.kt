package com.example.hibari.ui

import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.hibari.browser.HibariWebViewClient
import com.example.hibari.browser.WebViewFactory

@Composable
fun BrowserScreen(viewModel: BrowserViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    // Mutable holder so nested lambdas can access the live WebView reference
    var webView by remember { mutableStateOf<WebView?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            shadowElevation = 4.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                NavigationBar(
                    uiState = uiState,
                    onNavigate = { input ->
                        val url = viewModel.navigate(input)
                        webView?.loadUrl(url)
                    },
                    onBack = { webView?.goBack() },
                    onForward = { webView?.goForward() },
                    onRefreshOrStop = {
                        if (uiState.isLoading) webView?.stopLoading()
                        else webView?.reload()
                    },
                )
                if (uiState.isLoading && uiState.loadProgress < 100) {
                    LinearProgressIndicator(
                        progress = { uiState.loadProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context ->
                    val client = HibariWebViewClient(
                        onPageStarted = { url, _ -> viewModel.onPageStarted(url) },
                        onPageFinished = { url -> viewModel.onPageFinished(url) },
                        onNavigationStateChanged = { back, fwd ->
                            viewModel.onNavigationStateChanged(back, fwd)
                        },
                    )
                    WebViewFactory.create(context, client).also { wv ->
                        wv.webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                viewModel.onProgressChanged(newProgress)
                            }
                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                viewModel.onTitleReceived(title)
                            }
                        }
                        webView = wv
                        wv.loadUrl(uiState.currentUrl)
                    }
                },
                update = { wv ->
                    webView = wv
                    viewModel.onNavigationStateChanged(wv.canGoBack(), wv.canGoForward())
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun NavigationBar(
    uiState: BrowserUiState,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefreshOrStop: () -> Unit,
) {
    var addressText by remember { mutableStateOf(uiState.displayUrl) }
    var isEditing by remember { mutableStateOf(false) }

    // Keep address bar in sync when not editing
    LaunchedEffect(uiState.displayUrl) {
        if (!isEditing) addressText = uiState.displayUrl
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, enabled = uiState.canGoBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "戻る",
            )
        }
        IconButton(onClick = onForward, enabled = uiState.canGoForward) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "進む",
            )
        }
        OutlinedTextField(
            value = addressText,
            onValueChange = { addressText = it },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
                .onFocusChanged { isEditing = it.isFocused },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(
                onGo = { onNavigate(addressText) },
            ),
            placeholder = { Text("URLまたは検索キーワード", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        IconButton(onClick = onRefreshOrStop) {
            Icon(
                if (uiState.isLoading) Icons.Filled.Close else Icons.Filled.Refresh,
                contentDescription = if (uiState.isLoading) "停止" else "更新",
            )
        }
    }
}
