package com.shilapi.xcertplay.domain

import android.content.Context

/**
 * DNSPod (Tencent Cloud) zone record used for the Tesla-compatible HTTPS endpoint and its
 * automatically issued Let's Encrypt certificate.
 *
 * [zone] is the domain managed in DNSPod (`example.com`) and [record] is the host inside it
 * (`car`, or `@` for the apex); together they form [domain].
 */
data class OwnDomainConfig(
    val enabled: Boolean = false,
    val zone: String = "",
    val record: String = "",
    val secretId: String = "",
    val secretKey: String = "",
    val email: String = "",
) {
    /** Host part sent to DNSPod: `@` for the apex, otherwise the first label(s). */
    val recordHost: String get() = record.ifBlank { "@" }

    val challengeHost: String get() = "_acme-challenge.${recordHost.removePrefix("@")}".trimEnd('.')

    val domain: String get() = when {
        zone.isBlank() -> ""
        recordHost == "@" -> zone
        else -> "$recordHost.$zone"
    }

    /**
     * Each field is validated on its own so the settings screen can save one field at a time; an
     * empty field is not an error here, only a missing piece.
     */
    fun zoneError(): String? =
        if (zone.isNotBlank() && !ZONE.matches(zone)) {
            "域名格式不对，请填写 DNSPod 中托管的主域名，例如 example.com"
        } else {
            null
        }

    fun recordError(): String? =
        if (record.isNotBlank() && !RECORD.matches(record)) {
            "主机记录只能包含字母、数字、连字符；留空或 @ 表示主域名"
        } else {
            null
        }

    fun secretIdError(): String? =
        if (secretId.isNotBlank() && !SECRET.matches(secretId)) {
            "SecretId 格式不对，请在腾讯云访问管理控制台创建 API 密钥"
        } else {
            null
        }

    fun secretKeyError(): String? =
        if (secretKey.isNotBlank() && !SECRET.matches(secretKey)) {
            "SecretKey 格式不对，请重新复制 API 密钥"
        } else {
            null
        }

    fun emailError(): String? =
        if (email.isNotBlank() && !EMAIL.matches(email)) "证书邮箱格式不对" else null

    /** What is still missing before the feature can be switched on. */
    fun missingRequiredField(): String? = when {
        zone.isBlank() -> "请先填写 DNSPod 主域名"
        secretId.isBlank() -> "请先填写腾讯云 SecretId"
        secretKey.isBlank() -> "请先填写腾讯云 SecretKey"
        else -> null
    }

    /** Full check, used when enabling and by the scheduler. */
    fun validate(): String? = when {
        !enabled -> null
        else -> missingRequiredField()
            ?: zoneError()
            ?: recordError()
            ?: secretIdError()
            ?: secretKeyError()
            ?: emailError()
    }

    companion object {
        val ZONE = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+")
        val RECORD = Regex("[A-Za-z0-9@]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
        val SECRET = Regex("[A-Za-z0-9]{16,128}")
        val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
    }
}

internal object OwnDomainSettings {
    private const val PREFS = "wheelplay_own_domain"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ZONE = "zone"
    private const val KEY_RECORD = "record"
    private const val KEY_SECRET_ID = "secret_id"
    private const val KEY_SECRET_KEY = "secret_key"
    private const val KEY_EMAIL = "email"
    private const val KEY_RECORD_ADDRESS = "record_address"
    private const val KEY_RECORD_AT = "record_at"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): OwnDomainConfig {
        val p = prefs(context)
        return OwnDomainConfig(
            enabled = p.getBoolean(KEY_ENABLED, false),
            zone = p.getString(KEY_ZONE, "").orEmpty(),
            record = p.getString(KEY_RECORD, "").orEmpty(),
            secretId = p.getString(KEY_SECRET_ID, "").orEmpty(),
            secretKey = p.getString(KEY_SECRET_KEY, "").orEmpty(),
            email = p.getString(KEY_EMAIL, "").orEmpty(),
        )
    }

    fun save(context: Context, config: OwnDomainConfig) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_ZONE, config.zone)
            .putString(KEY_RECORD, config.record)
            .putString(KEY_SECRET_ID, config.secretId)
            .putString(KEY_SECRET_KEY, config.secretKey)
            .putString(KEY_EMAIL, config.email)
            .apply()
    }

    fun lastRecordAddress(context: Context): String? = prefs(context).getString(KEY_RECORD_ADDRESS, null)
    fun lastRecordAt(context: Context): Long = prefs(context).getLong(KEY_RECORD_AT, 0L)

    fun saveRecord(context: Context, address: String) {
        prefs(context).edit().putString(KEY_RECORD_ADDRESS, address)
            .putLong(KEY_RECORD_AT, System.currentTimeMillis()).apply()
    }
}
