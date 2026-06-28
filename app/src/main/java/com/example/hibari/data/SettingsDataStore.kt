package com.example.hibari.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "hibari_settings")

/**
 * Typed access to user settings via DataStore.
 *
 * All defaults are chosen for maximum security/privacy.
 */
class SettingsDataStore(private val context: Context) {

    private object Keys {
        val SECURE_DNS_ENABLED = booleanPreferencesKey("secure_dns_enabled")
        val DOH_PROVIDER_URL = stringPreferencesKey("doh_provider_url")
        val AD_BLOCK_ENABLED = booleanPreferencesKey("ad_block_enabled")
        val HTTPS_ONLY = booleanPreferencesKey("https_only")
        val BLOCK_THIRD_PARTY_COOKIES = booleanPreferencesKey("block_third_party_cookies")
        val SEARCH_ENGINE_URL = stringPreferencesKey("search_engine_url")
    }

    // ── Secure DNS ──────────────────────────────────────────────────────────
    val secureDnsEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.SECURE_DNS_ENABLED] ?: false }

    suspend fun setSecureDnsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SECURE_DNS_ENABLED] = enabled }
    }

    val dohProviderUrl: Flow<String> = context.dataStore.data
        .map { it[Keys.DOH_PROVIDER_URL] ?: "https://cloudflare-dns.com/dns-query" }

    suspend fun setDohProviderUrl(url: String) {
        context.dataStore.edit { it[Keys.DOH_PROVIDER_URL] = url }
    }

    // ── Ad Block ────────────────────────────────────────────────────────────
    val adBlockEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.AD_BLOCK_ENABLED] ?: true }

    suspend fun setAdBlockEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AD_BLOCK_ENABLED] = enabled }
    }

    // ── Security ────────────────────────────────────────────────────────────
    val httpsOnly: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.HTTPS_ONLY] ?: true }

    suspend fun setHttpsOnly(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HTTPS_ONLY] = enabled }
    }

    val blockThirdPartyCookies: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.BLOCK_THIRD_PARTY_COOKIES] ?: true }

    suspend fun setBlockThirdPartyCookies(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BLOCK_THIRD_PARTY_COOKIES] = enabled }
    }

    // ── General ─────────────────────────────────────────────────────────────
    val searchEngineUrl: Flow<String> = context.dataStore.data
        .map { it[Keys.SEARCH_ENGINE_URL] ?: "https://www.google.com/search?q=" }

    suspend fun setSearchEngineUrl(url: String) {
        context.dataStore.edit { it[Keys.SEARCH_ENGINE_URL] = url }
    }
}
