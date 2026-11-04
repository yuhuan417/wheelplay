package com.shilapi.xcertplay.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TencentCloudApiTest {
    // Fixed inputs so the signature can be compared with a second implementation if needed.
    private val timestamp = 1_700_000_000L
    private val payload = """{"Domain":"example.com","SubDomain":"car","RecordType":"A"}"""

    @Test
    fun theCanonicalRequestHasTheDocumentedShape() {
        val canonical = TencentCloudApi.canonicalRequest(payload)
        val lines = canonical.split("\n")

        assertEquals("POST", lines[0])
        assertEquals("/", lines[1])
        assertEquals("", lines[2])
        assertEquals("content-type:application/json; charset=utf-8", lines[3])
        assertEquals("host:dnspod.tencentcloudapi.com", lines[4])
        assertEquals("", lines[5])
        assertEquals("content-type;host", lines[6])
        assertEquals(TencentCloudApi.sha256Hex(payload), lines[7])
    }

    @Test
    fun theStringToSignUsesTheUtcDate() {
        val date = TencentCloudApi.utcDate(timestamp)
        val stringToSign = TencentCloudApi.stringToSign(
            date,
            timestamp,
            TencentCloudApi.canonicalRequest(payload),
        )
        val lines = stringToSign.split("\n")

        assertEquals("2023-11-14", date)
        assertEquals("TC3-HMAC-SHA256", lines[0])
        assertEquals("1700000000", lines[1])
        assertEquals("2023-11-14/dnspod/tc3_request", lines[2])
        assertEquals(64, lines[3].length)
    }

    @Test
    fun theSignatureIsStableAndHexEncoded() {
        val date = TencentCloudApi.utcDate(timestamp)
        val stringToSign = TencentCloudApi.stringToSign(
            date,
            timestamp,
            TencentCloudApi.canonicalRequest(payload),
        )

        val first = TencentCloudApi.signature("secretKey", date, stringToSign)
        val second = TencentCloudApi.signature("secretKey", date, stringToSign)

        assertEquals(first, second)
        assertEquals(64, first.length)
        assertTrue(first.all { it in "0123456789abcdef" })
    }

    @Test
    fun aDifferentSecretProducesADifferentSignature() {
        val date = TencentCloudApi.utcDate(timestamp)
        val stringToSign = TencentCloudApi.stringToSign(
            date,
            timestamp,
            TencentCloudApi.canonicalRequest(payload),
        )

        assertNotEquals(
            TencentCloudApi.signature("secretKey", date, stringToSign),
            TencentCloudApi.signature("otherKey", date, stringToSign),
        )
    }

    @Test
    fun theAuthorizationHeaderCarriesTheCredentialScope() {
        val header = TencentCloudApi.authorization("AKIDtest", "secretKey", timestamp, payload)

        assertTrue(header.startsWith("TC3-HMAC-SHA256 Credential=AKIDtest/2023-11-14/dnspod/tc3_request"))
        assertTrue("SignedHeaders=content-type;host" in header)
        assertTrue(header.substringAfter("Signature=").length == 64)
    }
}
