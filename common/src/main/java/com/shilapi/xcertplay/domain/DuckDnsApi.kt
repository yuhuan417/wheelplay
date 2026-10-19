package com.shilapi.xcertplay.domain

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * DuckDNS update API. The TXT record lives on the DuckDNS sub-domain and is also served for every
 * sub-sub-domain, which is what ACME's `_acme-challenge.<sub>.duckdns.org` lookup receives.
 *
 * Never log the URLs: the token is a query parameter.
 */
internal object DuckDnsApi {
    private const val API = "https://www.duckdns.org/update"

    data class Result(val ok: Boolean, val response: String)

    fun addressUrl(subdomain: String, token: String, address: String): String =
        "$API?domains=${encode(subdomain)}&token=${encode(token)}&ip=${encode(address)}&verbose=true"

    fun txtUrl(subdomain: String, token: String, value: String): String =
        "$API?domains=${encode(subdomain)}&token=${encode(token)}&txt=${encode(value)}&verbose=true"

    fun clearTxtUrl(subdomain: String, token: String): String =
        "$API?domains=${encode(subdomain)}&token=${encode(token)}&txt=&clear=true&verbose=true"

    fun update(
        url: String,
        openConnection: (String) -> HttpURLConnection = {
            URL(it).openConnection() as HttpURLConnection
        },
    ): Result {
        val connection = openConnection(url).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "WheelPlay/1.0")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(1024)
                while (text.length < 4096) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    text.append(buffer, 0, count)
                }
                text.toString().trim()
            }.orEmpty()
            Result(code in 200..299 && body.lineSequence().firstOrNull()?.trim() == "OK", body)
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
