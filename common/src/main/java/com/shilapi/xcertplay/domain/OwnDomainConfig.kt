package com.shilapi.xcertplay.domain

import android.content.Context

/** DuckDNS name + token for an automatically issued Let's Encrypt certificate. */
data class OwnDomainConfig(
    val enabled: Boolean = false,
    val subdomain: String = "",
    val token: String = "",
    val email: String = "",
    /** Optional override for the address published in the A record; blank = auto-detect the AP/bridge. */
    val addressOverride: String = "",
) {
    val domain: String get() = if (subdomain.isBlank()) "" else "$subdomain.duckdns.org"

    fun validate(): String? = when {
        !enabled -> null
        !SUBDOMAIN.matches(subdomain) -> "DuckDNS 子域名只能包含小写字母、数字和连字符"
        !TOKEN.matches(token) -> "DuckDNS Token 格式不对，请在 duckdns.org 页面复制"
        email.isNotBlank() && !EMAIL.matches(email) -> "证书邮箱格式不对"
        addressOverride.isNotBlank() && !isIpv4(addressOverride) -> "解析地址必须是 IPv4 地址"
        else -> null
    }

    companion object {
        val SUBDOMAIN = Regex("[a-z0-9][a-z0-9-]{0,62}")
        val TOKEN = Regex("[A-Za-z0-9-]{16,128}")
        val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

        fun isIpv4(value: String): Boolean {
            val parts = value.split('.')
            if (parts.size != 4) return false
            return parts.all { part ->
                part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                    part.toIntOrNull()?.let { it in 0..255 } == true
            }
        }
    }
}

internal object OwnDomainSettings {
    private const val PREFS = "wheelplay_own_domain"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SUBDOMAIN = "subdomain"
    private const val KEY_TOKEN = "token"
    private const val KEY_EMAIL = "email"
    private const val KEY_ADDRESS = "address_override"
    private const val KEY_RECORD_ADDRESS = "record_address"
    private const val KEY_RECORD_AT = "record_at"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): OwnDomainConfig {
        val p = prefs(context)
        return OwnDomainConfig(
            enabled = p.getBoolean(KEY_ENABLED, false),
            subdomain = p.getString(KEY_SUBDOMAIN, "").orEmpty(),
            token = p.getString(KEY_TOKEN, "").orEmpty(),
            email = p.getString(KEY_EMAIL, "").orEmpty(),
            addressOverride = p.getString(KEY_ADDRESS, "").orEmpty(),
        )
    }

    fun save(context: Context, config: OwnDomainConfig) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_SUBDOMAIN, config.subdomain)
            .putString(KEY_TOKEN, config.token)
            .putString(KEY_EMAIL, config.email)
            .putString(KEY_ADDRESS, config.addressOverride)
            .apply()
    }

    fun lastRecordAddress(context: Context): String? = prefs(context).getString(KEY_RECORD_ADDRESS, null)
    fun lastRecordAt(context: Context): Long = prefs(context).getLong(KEY_RECORD_AT, 0L)

    fun saveRecord(context: Context, address: String) {
        prefs(context).edit().putString(KEY_RECORD_ADDRESS, address)
            .putLong(KEY_RECORD_AT, System.currentTimeMillis()).apply()
    }
}
