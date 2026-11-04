package com.shilapi.xcertplay.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DnspodApiTest {
    private val config = OwnDomainConfig(
        enabled = true,
        zone = "example.com",
        record = "car",
        secretId = "AKIDexample1234567890",
        secretKey = "secretExample1234567890",
    )

    @Test
    fun theHostnameAndChallengeNamesAreDerived() {
        assertEquals("car.example.com", config.domain)
        assertEquals("car", config.recordHost)
        assertEquals("_acme-challenge.car", config.challengeHost)
    }

    @Test
    fun theApexRecordUsesTheBareDomain() {
        val apex = config.copy(record = "@")

        assertEquals("example.com", apex.domain)
        assertEquals("@", apex.recordHost)
        assertEquals("_acme-challenge", apex.challengeHost)
    }

    @Test
    fun theAddressPayloadMatchesTheDnspodContract() {
        val payload = JSONObject(DnspodApi.addressPayload("example.com", "car", "203.0.113.9"))

        assertEquals("example.com", payload.getString("Domain"))
        assertEquals("car", payload.getString("SubDomain"))
        assertEquals("A", payload.getString("RecordType"))
        assertEquals("默认", payload.getString("RecordLine"))
        assertEquals("203.0.113.9", payload.getString("Value"))
        assertEquals(600, payload.getInt("TTL"))
    }

    @Test
    fun theTxtPayloadKeepsTheChallengeValueVerbatim() {
        val payload = JSONObject(DnspodApi.txtPayload("example.com", "_acme-challenge.car", "abc-_DEF"))

        assertEquals("TXT", payload.getString("RecordType"))
        assertEquals("_acme-challenge.car", payload.getString("SubDomain"))
        assertEquals("abc-_DEF", payload.getString("Value"))
    }

    @Test
    fun theRecordListIsParsed() {
        val body = """
            {"Response":{"RecordList":[
              {"RecordId":123,"Value":"203.0.113.9","Name":"car"},
              {"RecordId":456,"Value":"198.51.100.7","Name":"car"}
            ],"RequestId":"r"}}
        """.trimIndent()

        val records = DnspodApi.parseRecords(body)

        assertEquals(2, records.size)
        assertEquals(123L, records[0].id)
        assertEquals("198.51.100.7", records[1].value)
    }

    @Test
    fun anEmptyRecordListIsNotAnError() {
        val body = """{"Response":{"RecordList":[],"RequestId":"r"}}"""

        assertTrue(DnspodApi.parseRecords(body).isEmpty())
    }

    @Test
    fun anErrorResponseSurfacesTheApiMessage() {
        val body = """
            {"Response":{"Error":{"Code":"AuthFailure.SignatureFailure",
            "Message":"The provided credentials could not be validated."},"RequestId":"r"}}
        """.trimIndent()

        val error = runCatching { DnspodApi.parseRecords(body) }.exceptionOrNull()

        assertTrue(error is DnspodApi.DnspodException)
        assertTrue(error!!.message!!.contains("AuthFailure.SignatureFailure"))
        assertTrue(error.message!!.contains("could not be validated"))
    }

    @Test
    fun theCreatedRecordIdIsRead() {
        assertEquals(789L, DnspodApi.parseCreatedId("""{"Response":{"RecordId":789,"RequestId":"r"}}"""))
    }

    @Test
    fun aMissingCreatedRecordIdIsRejected() {
        val error = runCatching {
            DnspodApi.parseCreatedId("""{"Response":{"RequestId":"r"}}""")
        }.exceptionOrNull()

        assertTrue(error is DnspodApi.DnspodException)
    }

    @Test
    fun anUnparseableBodyIsRejected() {
        val error = runCatching { DnspodApi.parseRecords("<html>bad gateway</html>") }.exceptionOrNull()

        assertTrue(error is DnspodApi.DnspodException)
        assertFalse(error!!.message.isNullOrBlank())
    }
}
