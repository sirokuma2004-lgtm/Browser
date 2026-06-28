package com.example.hibari.net

import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * DNS-over-HTTPS resolver wrapping OkHttp's DnsOverHttps.
 *
 * Security notes:
 *  - Resolves via the chosen DoH provider over HTTPS (TLS-encrypted).
 *  - resolvePrivateAddresses=false: private/loopback addresses are NOT
 *    forwarded to the DoH provider (they use system DNS or fail).
 *  - Known providers use bootstrap IPs to avoid the chicken-and-egg
 *    problem of resolving the DoH hostname with system DNS.
 */
class DohResolver(dohUrl: String = Preset.CLOUDFLARE.url) {

    // OkHttpClient shared by DoH. Intentionally separate from any proxy
    // configuration so DoH queries always go directly to the provider.
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var dns: DnsOverHttps = buildDns(dohUrl)

    /** Switch provider at runtime (e.g. from settings). Thread-safe via @Volatile. */
    fun updateProvider(url: String) {
        dns = buildDns(url)
    }

    /**
     * Resolve [hostname] to a list of addresses.
     * Blocking — must be called from a background thread / Dispatchers.IO.
     */
    fun resolve(hostname: String): List<InetAddress> = dns.lookup(hostname)

    private fun buildDns(url: String): DnsOverHttps {
        val builder = DnsOverHttps.Builder()
            .client(baseClient)
            .url(url.toHttpUrl())
            .includeIPv6(false)
            .resolvePrivateAddresses(false)

        // Pre-seed bootstrap IPs for well-known providers so we don't need
        // system DNS to resolve the DoH server on first use.
        bootstrapIpsFor(url).takeIf { it.isNotEmpty() }
            ?.let { builder.bootstrapDnsHosts(it) }

        return builder.build()
    }

    private fun bootstrapIpsFor(url: String): List<InetAddress> = when {
        url.contains("cloudflare") -> listOf("1.1.1.1", "1.0.0.1")
        url.contains("dns.google") || url.contains("8.8.8") -> listOf("8.8.8.8", "8.8.4.4")
        url.contains("adguard") -> listOf("94.140.14.14", "94.140.15.15")
        url.contains("nextdns") -> listOf("45.90.28.0", "45.90.30.0")
        else -> emptyList()
    }.map { InetAddress.getByName(it) }

    // ── Presets ──────────────────────────────────────────────────────────────

    enum class Preset(val url: String, val displayName: String) {
        CLOUDFLARE("https://cloudflare-dns.com/dns-query", "Cloudflare (1.1.1.1)"),
        GOOGLE("https://dns.google/dns-query", "Google (8.8.8.8)"),
        ADGUARD("https://dns.adguard-dns.com/dns-query", "AdGuard DNS"),
        NEXTDNS("https://dns.nextdns.io/dns-query", "NextDNS");

        companion object {
            fun fromUrl(url: String): Preset? = entries.firstOrNull { it.url == url }
        }
    }
}
