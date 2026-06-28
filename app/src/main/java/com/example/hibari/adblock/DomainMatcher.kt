package com.example.hibari.adblock

/**
 * M3: O(1)-average domain matching for ad/tracker blocking.
 *
 * Algorithm (DESIGN.md §5.4):
 *  1. Exact match: blocklist.contains(host)
 *  2. Subdomain walk: strip leftmost label and repeat until no more labels.
 *     e.g. "sub.ads.example.com" → check "ads.example.com" → "example.com"
 *  3. Allowlist takes priority over blocklist.
 *
 * This is intentionally a standalone class with no Android dependencies so
 * it can be unit-tested on the JVM without an emulator.
 */
class DomainMatcher {

    private val blocklist = HashSet<String>()
    private val allowlist = HashSet<String>()

    fun load(blocked: Collection<String>, allowed: Collection<String> = emptyList()) {
        blocklist.clear()
        blocklist.addAll(blocked)
        allowlist.clear()
        allowlist.addAll(allowed)
    }

    fun shouldBlock(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase().trimEnd('.')
        if (allowlist.contains(h) || walkAllowlist(h)) return false
        return blocklist.contains(h) || walkBlocklist(h)
    }

    private fun walkBlocklist(host: String): Boolean {
        var dot = host.indexOf('.')
        while (dot >= 0) {
            val parent = host.substring(dot + 1)
            if (blocklist.contains(parent)) return true
            dot = host.indexOf('.', dot + 1)
        }
        return false
    }

    private fun walkAllowlist(host: String): Boolean {
        var dot = host.indexOf('.')
        while (dot >= 0) {
            val parent = host.substring(dot + 1)
            if (allowlist.contains(parent)) return true
            dot = host.indexOf('.', dot + 1)
        }
        return false
    }
}
