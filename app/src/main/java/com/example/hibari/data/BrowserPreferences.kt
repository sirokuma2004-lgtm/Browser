package com.example.hibari.data

import android.content.Context
import android.content.SharedPreferences
import com.example.hibari.net.DohResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BrowserPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _secureDnsEnabled = MutableStateFlow(prefs.getBoolean(KEY_SECURE_DNS, false))
    val secureDnsEnabled: StateFlow<Boolean> = _secureDnsEnabled.asStateFlow()

    private val _dohProviderUrl = MutableStateFlow(
        prefs.getString(KEY_DOH_PROVIDER_URL, DohResolver.Preset.CLOUDFLARE.url)!!
    )
    val dohProviderUrl: StateFlow<String> = _dohProviderUrl.asStateFlow()

    private val _httpsOnly = MutableStateFlow(prefs.getBoolean(KEY_HTTPS_ONLY, true))
    val httpsOnly: StateFlow<Boolean> = _httpsOnly.asStateFlow()

    private val _blockThirdPartyCookies =
        MutableStateFlow(prefs.getBoolean(KEY_BLOCK_3P_COOKIES, true))
    val blockThirdPartyCookies: StateFlow<Boolean> = _blockThirdPartyCookies.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            KEY_SECURE_DNS -> _secureDnsEnabled.value = prefs.getBoolean(key, false)
            KEY_DOH_PROVIDER_URL ->
                _dohProviderUrl.value = prefs.getString(key, DohResolver.Preset.CLOUDFLARE.url)!!
            KEY_HTTPS_ONLY -> _httpsOnly.value = prefs.getBoolean(key, true)
            KEY_BLOCK_3P_COOKIES -> _blockThirdPartyCookies.value = prefs.getBoolean(key, true)
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun setSecureDnsEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_SECURE_DNS, value).apply()
    fun setDohProviderUrl(value: String) = prefs.edit().putString(KEY_DOH_PROVIDER_URL, value).apply()
    fun setHttpsOnly(value: Boolean) = prefs.edit().putBoolean(KEY_HTTPS_ONLY, value).apply()
    fun setBlockThirdPartyCookies(value: Boolean) =
        prefs.edit().putBoolean(KEY_BLOCK_3P_COOKIES, value).apply()

    companion object {
        const val PREFS_NAME = "hibari_prefs"
        const val KEY_SECURE_DNS = "secure_dns_enabled"
        const val KEY_DOH_PROVIDER_URL = "doh_provider_url"
        const val KEY_HTTPS_ONLY = "https_only"
        const val KEY_BLOCK_3P_COOKIES = "block_third_party_cookies"
    }
}
