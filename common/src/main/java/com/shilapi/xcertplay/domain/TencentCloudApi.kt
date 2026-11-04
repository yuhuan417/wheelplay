package com.shilapi.xcertplay.domain

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Tencent Cloud API 3.0 client for DNSPod, signed with TC3-HMAC-SHA256.
 *
 * The app builds offline, so no SDK is available: the canonical request, the string to sign and the
 * signing key chain are implemented here and covered by unit tests. Secrets never reach the log.
 */
internal object TencentCloudApi {
    const val HOST = "dnspod.tencentcloudapi.com"
    private const val SERVICE = "dnspod"
    private const val VERSION = "2021-03-23"
    private const val ALGORITHM = "TC3-HMAC-SHA256"
    private const val CONTENT_TYPE = "application/json; charset=utf-8"
    private const val SIGNED_HEADERS = "content-type;host"

    data class Result(val ok: Boolean, val body: String)

    /** `Authorization` value; split out so the signing can be tested without network access. */
    internal fun authorization(
        secretId: String,
        secretKey: String,
        timestamp: Long,
        payload: String,
    ): String {
        val date = utcDate(timestamp)
        val canonical = canonicalRequest(payload)
        val signature = signature(secretKey, date, stringToSign(date, timestamp, canonical))
        return "$ALGORITHM Credential=$secretId/$date/$SERVICE/tc3_request, " +
            "SignedHeaders=$SIGNED_HEADERS, Signature=$signature"
    }

    internal fun canonicalRequest(payload: String): String = buildString {
        append("POST\n")
        append("/\n")
        append("\n")
        append("content-type:$CONTENT_TYPE\n")
        append("host:$HOST\n")
        append("\n")
        append("$SIGNED_HEADERS\n")
        append(sha256Hex(payload))
    }

    internal fun stringToSign(date: String, timestamp: Long, canonicalRequest: String): String =
        "$ALGORITHM\n$timestamp\n$date/$SERVICE/tc3_request\n${sha256Hex(canonicalRequest)}"

    internal fun signature(secretKey: String, date: String, stringToSign: String): String {
        val secretDate = hmac(("TC3" + secretKey).toByteArray(), date)
        val secretService = hmac(secretDate, SERVICE)
        val secretSigning = hmac(secretService, "tc3_request")
        return hmac(secretSigning, stringToSign).toHex()
    }

    internal fun utcDate(timestamp: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(timestamp * 1000))

    internal fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toHex()

    private fun hmac(key: ByteArray, value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value.toByteArray())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /**
     * Calls one DNSPod action. [openConnection] is injectable so tests can capture the request
     * without touching the network.
     */
    fun call(
        secretId: String,
        secretKey: String,
        action: String,
        payload: String,
        openConnection: (String) -> HttpURLConnection = {
            URL(it).openConnection() as HttpURLConnection
        },
    ): Result {
        val timestamp = System.currentTimeMillis() / 1000
        val connection = openConnection("https://$HOST")
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", CONTENT_TYPE)
            connection.setRequestProperty("Host", HOST)
            connection.setRequestProperty("X-TC-Action", action)
            connection.setRequestProperty("X-TC-Version", VERSION)
            connection.setRequestProperty("X-TC-Timestamp", timestamp.toString())
            connection.setRequestProperty(
                "Authorization",
                authorization(secretId, secretKey, timestamp, payload),
            )
            connection.outputStream.use { it.write(payload.toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(1024)
                while (text.length < 8192) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    text.append(buffer, 0, count)
                }
                text.toString()
            }.orEmpty()
            Result(code in 200..299, body)
        } finally {
            connection.disconnect()
        }
    }
}
