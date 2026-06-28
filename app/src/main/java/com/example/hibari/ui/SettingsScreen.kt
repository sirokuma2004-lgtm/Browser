package com.example.hibari.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.hibari.net.DohResolver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: BrowserViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settingsState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Secure DNS ────────────────────────────────────────────────
            SectionHeader("セキュア DNS（DoH）")

            SwitchRow(
                title = "セキュア DNS を使用",
                subtitle = "名前解決を暗号化（Chrome の「セキュア DNS」相当）",
                checked = settings.secureDnsEnabled,
                onCheckedChange = { viewModel.setSecureDnsEnabled(it) },
            )

            if (settings.secureDnsEnabled) {
                Spacer(modifier = Modifier.height(4.dp))
                DohProviderPicker(
                    currentUrl = settings.dohProviderUrl,
                    onProviderSelected = { viewModel.setDohProvider(it) },
                )

                if (!settings.proxyOverrideSupported) {
                    InfoCard(
                        "この端末の WebView バージョンは ProxyController に対応していません。\n" +
                            "Android の「プライベート DNS」（設定 → ネットワーク → 詳細設定 → プライベートDNS）を\n" +
                            "「自動」または任意のプロバイダに設定することでセキュアDNSを利用できます。",
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── Ad Block ─────────────────────────────────────────────────
            SectionHeader("広告ブロック")

            SwitchRow(
                title = "広告・トラッカをブロック",
                subtitle = "ブラウザ内でリクエストを遮断（VPN 不使用）",
                checked = settings.adBlockEnabled,
                onCheckedChange = { viewModel.setAdBlockEnabled(it) },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── Security ─────────────────────────────────────────────────
            SectionHeader("セキュリティ")

            SwitchRow(
                title = "HTTPS のみモード",
                subtitle = "http:// サイトを https:// にアップグレード",
                checked = settings.httpsOnly,
                onCheckedChange = { viewModel.setHttpsOnly(it) },
            )

            SwitchRow(
                title = "サードパーティ Cookie をブロック",
                subtitle = "トラッキング Cookie を制限",
                checked = settings.blockThirdPartyCookies,
                onCheckedChange = { viewModel.setBlockThirdPartyCookies(it) },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── Data ─────────────────────────────────────────────────────
            SectionHeader("データ")

            TextRow(
                title = "閲覧データを削除",
                subtitle = "履歴・Cookie・キャッシュを消去",
                onClick = { viewModel.clearBrowsingData() },
            )
        }
    }
}

// ── DoH provider picker ───────────────────────────────────────────────────────

@Composable
private fun DohProviderPicker(
    currentUrl: String,
    onProviderSelected: (String) -> Unit,
) {
    var showCustomDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text("プロバイダ", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        DohResolver.Preset.entries.forEach { preset ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onProviderSelected(preset.url) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = currentUrl == preset.url,
                    onClick = { onProviderSelected(preset.url) },
                )
                Column {
                    Text(preset.displayName, style = MaterialTheme.typography.bodyMedium)
                    Text(preset.url, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Custom URL option
        val isCustom = DohResolver.Preset.fromUrl(currentUrl) == null
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showCustomDialog = true }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = isCustom, onClick = { showCustomDialog = true })
            Column {
                Text("カスタム URL", style = MaterialTheme.typography.bodyMedium)
                if (isCustom) {
                    Text(currentUrl, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (showCustomDialog) {
        CustomDohDialog(
            initial = if (DohResolver.Preset.fromUrl(currentUrl) == null) currentUrl else "",
            onConfirm = { url ->
                onProviderSelected(url)
                showCustomDialog = false
            },
            onDismiss = { showCustomDialog = false },
        )
    }
}

@Composable
private fun CustomDohDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("カスタム DoH URL") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("DoH テンプレート URL") },
                placeholder = { Text("https://example.com/dns-query") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.startsWith("https://")) onConfirm(text) },
                enabled = text.startsWith("https://"),
            ) { Text("適用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

// ── Shared composables ────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TextRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoCard(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = "⚠ $message",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
