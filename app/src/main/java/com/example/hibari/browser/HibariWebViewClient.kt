package com.example.hibari.browser

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Core WebViewClient for Hibari.
 *
 * Security invariants enforced here:
 *  - HTTPS-Only: http:// URLs are silently upgraded to https:// (when httpsOnly=true)
 *  - SSL errors are NEVER silently accepted (invariant #2)
 *
 * Ad filtering is handled at the DNS level via AdGuard DNS (DESIGN.md §5.4).
 */
class HibariWebViewClient(
    private val onPageStarted: (url: String?, favicon: Bitmap?) -> Unit = { _, _ -> },
    private val onPageFinished: (url: String?) -> Unit = {},
    private val onNavigationStateChanged: (canGoBack: Boolean, canGoForward: Boolean) -> Unit = { _, _ -> },
    private var httpsOnly: Boolean = true,
) : WebViewClient() {

    /** Called from BrowserScreen's AndroidView update block when the setting changes. */
    fun updateHttpsOnly(enabled: Boolean) {
        httpsOnly = enabled
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!httpsOnly) return false
        val uri = request.url ?: return false
        if (uri.scheme == "http") {
            // Silently upgrade http:// to https://. If the server has no HTTPS, the user
            // will see the SSL error page — we do NOT fall back to HTTP.
            val upgraded = uri.buildUpon().scheme("https").build()
            view.loadUrl(upgraded.toString())
            return true
        }
        return false
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onPageStarted(url, favicon)
        onNavigationStateChanged(view.canGoBack(), view.canGoForward())
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        onPageFinished(url)
        onNavigationStateChanged(view.canGoBack(), view.canGoForward())
    }

    /**
     * SECURITY INVARIANT #2: SSL errors MUST NOT be silently accepted.
     *
     * Calling handler.proceed() would expose the user to MITM attacks.
     * We always cancel and let the default error page render.
     */
    override fun onReceivedSslError(
        view: WebView,
        handler: SslErrorHandler,
        error: SslError,
    ) {
        handler.cancel()
    }
}
