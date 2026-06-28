package com.example.hibari.net

import android.content.Context
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Coordinates the LocalProxyServer lifecycle with WebView's ProxyController.
 *
 * Usage (from BrowserViewModel):
 *   val port = proxyManager.startProxy(context, dohUrl)
 *   proxyManager.applyToWebView(context) { /* safe to navigate */ }
 *
 * Fallback (DESIGN.md §5.3):
 *   If PROXY_OVERRIDE is not supported, [applyToWebView] returns false.
 *   Callers should then surface the "use Android Private DNS" guidance.
 *
 * Security invariants:
 *   - Proxy is on 127.0.0.1 only (enforced by LocalProxyServer).
 *   - clearProxyOverride() is called on stop to restore default behaviour.
 */
class ProxyManager(private val scope: CoroutineScope) {

    val resolver = DohResolver()
    private var proxyServer: LocalProxyServer? = null

    val isProxyOverrideSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    /**
     * Start the local proxy with the given DoH provider URL.
     * Returns the port number, or -1 on failure.
     */
    suspend fun start(dohUrl: String): Int {
        stop() // Stop any existing server first
        resolver.updateProvider(dohUrl)
        val server = LocalProxyServer(resolver, scope)
        return try {
            val port = server.start()
            proxyServer = server
            port
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Apply proxy to all WebViews via ProxyController.
     *
     * The [onApplied] callback fires when the proxy configuration is active —
     * only navigate WebViews AFTER this callback.
     *
     * Returns false if PROXY_OVERRIDE is not supported (caller should show
     * the Private DNS fallback guidance to the user).
     */
    fun applyToWebView(port: Int, onApplied: () -> Unit): Boolean {
        if (!isProxyOverrideSupported || port <= 0) return false

        val proxyConfig = ProxyConfig.Builder()
            .addProxyRule("http://127.0.0.1:$port")
            .bypassSimpleHostnames() // don't proxy "localhost" etc.
            .build()

        ProxyController.getInstance().setProxyOverride(
            proxyConfig,
            Executors.newSingleThreadExecutor(),
        ) {
            onApplied()
        }
        return true
    }

    /** Clear the proxy override and stop the server. */
    suspend fun stop() = withContext(Dispatchers.Main) {
        if (isProxyOverrideSupported) {
            ProxyController.getInstance().clearProxyOverride(
                Executors.newSingleThreadExecutor(),
            ) { /* cleared */ }
        }
        withContext(Dispatchers.IO) { proxyServer?.stop() }
        proxyServer = null
    }

    /** Change the DoH provider without restarting the TCP server. */
    fun updateDohProvider(url: String) {
        resolver.updateProvider(url)
    }
}
