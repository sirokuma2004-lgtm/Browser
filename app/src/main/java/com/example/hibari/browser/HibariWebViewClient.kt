package com.example.hibari.browser

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Core WebViewClient for Hibari.
 *
 * Responsibilities:
 *  - Forward page-lifecycle events (start/finish) to the ViewModel layer
 *  - Gate shouldInterceptRequest for ad-blocking (M3, stub for M0)
 *  - Enforce SSL error policy: NEVER call handler.proceed() (invariant #2)
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
     * M3 hook: ad/tracker blocking will be implemented here.
     *
     * Contract: this method is for BLOCK/PASS decisions only. It must not attempt
     * to read or modify POST bodies (the API does not expose them).
     * Return null to let WebView handle the request normally.
     */
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        // M3: DomainMatcher.shouldBlock(request.url.host) → return emptyResponse()
        return null
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

    // Helper: empty WebResourceResponse used by the ad blocker (M3)
    @Suppress("unused")
    private fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", null)
}
