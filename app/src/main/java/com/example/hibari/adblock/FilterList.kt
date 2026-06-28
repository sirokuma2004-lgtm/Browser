package com.example.hibari.adblock

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * M3: Filter list manager — download, cache, and feed domains to DomainMatcher.
 *
 * Supported sources (DESIGN.md §5.4):
 *  - StevenBlack/hosts (consolidated)
 *  - AdGuard DNS filter
 *  - OISD
 *  - EasyList / EasyPrivacy (domain extraction only)
 *
 * Each list is cached locally. On startup the cached version is loaded
 * immediately (non-blocking for the UI), and a background refresh is
 * triggered if the cache is stale.
 */
class FilterList(private val context: Context, private val matcher: DomainMatcher) {

    data class Source(
        val id: String,
        val displayName: String,
        val url: String,
        val enabled: Boolean = true,
    )

    val defaultSources = listOf(
        Source(
            id = "stevenblack",
            displayName = "StevenBlack/hosts (統合)",
            url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        ),
        Source(
            id = "oisd",
            displayName = "OISD Basic",
            url = "https://dbl.oisd.nl/basic/",
        ),
    )

    suspend fun loadFromCache() = withContext(Dispatchers.IO) {
        // TODO M3: read cached domain file from context.filesDir, parse, call matcher.load()
    }

    suspend fun refresh(sources: List<Source> = defaultSources) = withContext(Dispatchers.IO) {
        // TODO M3: download each enabled source, parse domains, merge, write cache, reload matcher
    }

    private fun parseHosts(raw: String): List<String> {
        return raw.lineSequence()
            .filterNot { it.startsWith('#') || it.isBlank() }
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                // "0.0.0.0 ads.example.com" or "127.0.0.1 ads.example.com"
                if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) {
                    parts[1].lowercase().takeIf { it.isNotBlank() && it != "localhost" }
                } else null
            }
            .toList()
    }
}
