package com.example.hibari.browser

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.example.hibari.BuildConfig

/**
 * Creates WebView instances with hardened settings applied consistently.
 * All WebViews in the app MUST be created through this factory so that no
 * security-relevant setting is accidentally omitted.
 *
 * Security invariants enforced here:
 *  - No addJavascriptInterface exposed to web content
 *  - file:// access fully disabled
 *  - Safe Browsing enabled (when supported)
 *  - Mixed content blocked (MIXED_CONTENT_NEVER_ALLOW)
 *  - WebContentsDebugging disabled in release builds
 */
object WebViewFactory {

    fun create(
        context: Context,
        client: HibariWebViewClient,
        blockThirdPartyCookies: Boolean = true,
    ): WebView {
        if (!BuildConfig.DEBUG) {
            // Disable WebView remote debugging in release; this is a process-wide static call.
            WebView.setWebContentsDebuggingEnabled(false)
        }

        return WebView(context).apply {
            webViewClient = client

            settings.apply {
                // JavaScript is required for modern web pages.
                // NOTE: addJavascriptInterface is intentionally NOT called — no JS bridge
                // is ever exposed to web content (security invariant #1).
                javaScriptEnabled = true

                // Disable all file:// access (security invariant #3)
                allowFileAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false

                // Disable content:// URI access
                allowContentAccess = false

                // Block mixed content: HTTPS pages must not load HTTP sub-resources
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

                // Geolocation is disabled by default; the WebChromeClient will prompt
                // on a per-request basis when the setting is re-enabled by the user.
                setGeolocationEnabled(false)

                // DOM storage for session state (no IndexedDB / Web SQL)
                domStorageEnabled = true
                databaseEnabled = false

                // Standard cache behaviour
                cacheMode = WebSettings.LOAD_DEFAULT

                // Suppress the default "powered by" headers that leak app info
                userAgentString = buildUserAgent(userAgentString)
            }

            // Safe Browsing — enable via compat API when the WebView version supports it.
            // The manifest meta-data key "android.webkit.WebView.EnableSafeBrowsing" also
            // opts-in at the manifest level; this call adds the runtime confirmation.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
                WebSettingsCompat.setSafeBrowsingEnabled(this, true)
            }

            // Third-party cookie policy (security invariant)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, !blockThirdPartyCookies)
        }
    }

    /**
     * Update the third-party cookie policy on an existing WebView.
     * Call from the AndroidView update block when the setting changes.
     */
    fun applyThirdPartyCookies(webView: WebView, blockThirdPartyCookies: Boolean) {
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, !blockThirdPartyCookies)
    }

    private fun buildUserAgent(defaultUa: String): String {
        // Keep the default UA (Chromium-based) so sites render correctly.
        // Append "Hibari" so the app can be identified in server logs if needed,
        // but strip the "wv" token that marks embedded WebViews to reduce fingerprinting.
        return defaultUa
            .replace("; wv)", ")")
            .replace("wv ", "")
            .trim()
            .let { if (it.contains("Hibari")) it else "$it Hibari/1.0" }
    }
}
