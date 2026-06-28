package com.example.hibari.browser

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Core WebViewClient for Hibari.
 *
 * Responsibilities:
 *  - Forward page-lifecycle events (start/finish) to the ViewModel layer
 *  - Enforce SSL error policy: NEVER call handler.proceed() (invariant #2)
 *
 * Ad filtering is handled at the DNS level via AdGuard DNS (DoH provider).
 * shouldInterceptRequest is not used for ad blocking (DESIGN.md §5.4).
 */
class HibariWebViewClient(
    private val onPageStarted: (url: String?, favicon: Bitmap?) -> Unit = { _, _ -> },
    private val onPageFinished: (url: String?) -> Unit = {},
    private val onNavigationStateChanged: (canGoBack: Boolean, canGoForward: Boolean) -> Unit = { _, _ -> },
) : WebViewClient() {

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
