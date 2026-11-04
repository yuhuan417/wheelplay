package com.shilapi.xcertplay.domain

import org.json.JSONObject
import java.net.HttpURLConnection

/** DNSPod record operations on top of the signed Tencent Cloud API. */
internal object DnspodApi {
    /** DNS-01 validation reads the TXT record from public resolvers, so keep the TTL short. */
    private const val TTL = 600
    private const val A = "A"
    private const val TXT = "TXT"
    /** DNSPod's default line; required by CreateRecord/ModifyRecord. */
    private const val DEFAULT_LINE = "默认"

    data class Record(val id: Long, val value: String)

    class DnspodException(message: String) : Exception(message)

    fun addressPayload(zone: String, host: String, address: String): String = JSONObject()
        .put("Domain", zone)
        .put("SubDomain", host)
        .put("RecordType", A)
        .put("RecordLine", DEFAULT_LINE)
        .put("Value", address)
        .put("TTL", TTL)
        .toString()

    fun modifyPayload(zone: String, host: String, recordId: Long, address: String): String = JSONObject()
        .put("Domain", zone)
        .put("RecordId", recordId)
        .put("SubDomain", host)
        .put("RecordType", A)
        .put("RecordLine", DEFAULT_LINE)
        .put("Value", address)
        .put("TTL", TTL)
        .toString()

    fun listPayload(zone: String, host: String, recordType: String): String = JSONObject()
        .put("Domain", zone)
        .put("SubDomain", host)
        .put("RecordType", recordType)
        // Empty results are normal while the challenge record is being (re)created.
        .put("ErrorOnEmpty", "no")
        .toString()

    fun txtPayload(zone: String, host: String, value: String): String = JSONObject()
        .put("Domain", zone)
        .put("SubDomain", host)
        .put("RecordType", TXT)
        .put("RecordLine", DEFAULT_LINE)
        .put("Value", value)
        .put("TTL", TTL)
        .toString()

    fun deletePayload(zone: String, recordId: Long): String = JSONObject()
        .put("Domain", zone)
        .put("RecordId", recordId)
        .toString()

    /** Extracts `Response.RecordList`; throws with the API's own message on an error response. */
    fun parseRecords(body: String): List<Record> {
        val response = response(body)
        val list = response.optJSONArray("RecordList") ?: return emptyList()
        return (0 until list.length()).mapNotNull { index ->
            val item = list.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optLong("RecordId", 0)
            if (id == 0L) return@mapNotNull null
            Record(id, item.optString("Value").orEmpty())
        }
    }

    /** Reads `Response.RecordId` from a CreateRecord reply. */
    fun parseCreatedId(body: String): Long {
        val id = response(body).optLong("RecordId", 0)
        if (id == 0L) throw DnspodException("DNSPod 未返回记录 ID")
        return id
    }

    private fun response(body: String): JSONObject {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: throw DnspodException("DNSPod 返回了无法解析的内容")
        val response = root.optJSONObject("Response")
            ?: throw DnspodException("DNSPod 返回了无法解析的内容")
        response.optJSONObject("Error")?.let { error ->
            val code = error.optString("Code").orEmpty()
            val message = error.optString("Message").orEmpty()
            throw DnspodException(if (message.isBlank()) code else "$code $message".trim())
        }
        return response
    }

    fun call(
        config: OwnDomainConfig,
        action: String,
        payload: String,
        openConnection: (String) -> HttpURLConnection,
    ): String {
        val result = TencentCloudApi.call(
            secretId = config.secretId,
            secretKey = config.secretKey,
            action = action,
            payload = payload,
            openConnection = openConnection,
        )
        // Error responses still carry a body with the reason; parse it before judging the status.
        if (!result.ok && !result.body.contains("\"Error\"")) {
            throw DnspodException("DNSPod HTTP ${result.ok}")
        }
        return result.body
    }

    /** Points `host.zone` at [address], modifying the existing A record when there is one. */
    fun updateAddress(
        config: OwnDomainConfig,
        address: String,
        openConnection: (String) -> HttpURLConnection,
    ) {
        val existing = parseRecords(
            call(config, "DescribeRecordList", listPayload(config.zone, config.recordHost, A), openConnection),
        )
        val match = existing.firstOrNull { it.value == address }
        if (match != null) return
        val current = existing.firstOrNull()
        if (current == null) {
            call(config, "CreateRecord", addressPayload(config.zone, config.recordHost, address), openConnection)
        } else {
            call(
                config,
                "ModifyRecord",
                modifyPayload(config.zone, config.recordHost, current.id, address),
                openConnection,
            )
        }
    }

    /** Publishes the DNS-01 challenge, replacing any stale value with the same name. */
    fun setChallengeTxt(
        config: OwnDomainConfig,
        value: String,
        openConnection: (String) -> HttpURLConnection,
    ) {
        val host = config.challengeHost
        val existing = parseRecords(
            call(config, "DescribeRecordList", listPayload(config.zone, host, TXT), openConnection),
        )
        existing.filter { it.value != value }.forEach { record ->
            runCatching {
                call(config, "DeleteRecord", deletePayload(config.zone, record.id), openConnection)
            }
        }
        call(config, "CreateRecord", txtPayload(config.zone, host, value), openConnection)
    }

    fun clearChallengeTxt(
        config: OwnDomainConfig,
        openConnection: (String) -> HttpURLConnection,
    ) {
        val host = config.challengeHost
        parseRecords(
            call(config, "DescribeRecordList", listPayload(config.zone, host, TXT), openConnection),
        ).forEach { record ->
            runCatching {
                call(config, "DeleteRecord", deletePayload(config.zone, record.id), openConnection)
            }
        }
    }
}
