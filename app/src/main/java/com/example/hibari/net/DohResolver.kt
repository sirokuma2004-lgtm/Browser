package com.example.hibari.net

import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress

/**
 * M2: DNS-over-HTTPS resolver wrapping OkHttp's DnsOverHttps.
 *
 * Preset providers (matching DESIGN.md §5.3):
 *  - Cloudflare  — https://cloudflare-dns.com/dns-query
 *  - Google      — https://dns.google/dns-query
 *  - AdGuard     — https://dns.adguard.com/dns-query
 *  - NextDNS     — https://dns.nextdns.io/<id>/dns-query
 *  - Custom URL  — user-supplied DoH template
 *
 * Resolution results are cached respecting DNS TTL.
 */
class DohResolver(private val dohUrl: String) {

    private val bootstrapClient = OkHttpClient.Builder().build()

    // DnsOverHttps instance — rebuilt when the provider URL changes
    private var dns: DnsOverHttps? = null

    fun init() {
        // TODO M2: construct DnsOverHttps with bootstrapClient and dohUrl
        // dns = DnsOverHttps.Builder()
        //     .client(bootstrapClient)
        //     .url(dohUrl.toHttpUrl())
        //     .includeIPv6(true)
        //     .build()
        throw NotImplementedError("DohResolver will be implemented in M2")
    }

    suspend fun resolve(hostname: String): List<InetAddress> {
        return dns?.lookup(hostname) ?: throw NotImplementedError("DohResolver not initialised (M2)")
    }

    enum class Preset(val url: String, val displayName: String) {
        CLOUDFLARE("https://cloudflare-dns.com/dns-query", "Cloudflare (1.1.1.1)"),
        GOOGLE("https://dns.google/dns-query", "Google (8.8.8.8)"),
        ADGUARD("https://dns.adguard.com/dns-query", "AdGuard DNS"),
        NEXTDNS("https://dns.nextdns.io/dns-query", "NextDNS"),
    }
}
