package com.shilapi.xcertplay.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class OwnDomainConfigTest {
    private val secretId = "AKIDexample1234567890"
    private val secretKey = "secretExample1234567890"

    private fun config(
        zone: String = "example.com",
        record: String = "car",
        id: String = secretId,
        key: String = secretKey,
        email: String = "",
    ) = OwnDomainConfig(
        enabled = true,
        zone = zone,
        record = record,
        secretId = id,
        secretKey = key,
        email = email,
    )

    @Test
    fun disabledConfigIsAlwaysValid() {
        assertNull(
            OwnDomainConfig(enabled = false, zone = "Bad Zone", record = "x", secretId = "", secretKey = "")
                .validate(),
        )
    }

    @Test
    fun acceptsAZoneWithAHostRecord() {
        assertNull(config().validate())
        assertEquals("car.example.com", config().domain)
        assertEquals("_acme-challenge.car", config().challengeHost)
    }

    @Test
    fun anEmptyRecordMeansTheApex() {
        assertNull(config(record = "").validate())
        assertEquals("example.com", config(record = "").domain)
        assertEquals("_acme-challenge", config(record = "").challengeHost)
    }

    @Test
    fun rejectsMalformedZonesAndRecords() {
        assertNotNull(config(zone = "example").validate())
        assertNotNull(config(zone = "exa mple.com").validate())
        assertNotNull(config(zone = "Example.com").validate())
        assertNotNull(config(record = "car_bay").validate())
        assertNotNull(config(record = "-car").validate())
    }

    @Test
    fun rejectsMissingOrMalformedCredentials() {
        assertNotNull(config(id = "").validate())
        assertNotNull(config(key = "").validate())
        assertNotNull(config(key = "has space in it").validate())
        assertNotNull(config(key = "short").validate())
    }

    @Test
    fun eachFieldOnlyValidatesItselfSoTheScreenCanSaveProgress() {
        // A half-filled configuration must be storable: the user edits one field at a time.
        val partial = OwnDomainConfig(enabled = false, zone = "example.com")

        assertNull(partial.zoneError())
        assertNull(partial.recordError())
        assertNull(partial.secretIdError())
        assertNull(partial.secretKeyError())
        assertNotNull(partial.missingRequiredField())
    }

    @Test
    fun theMissingFieldIsNamedBeforeEnabling() {
        assertTrue(
            config().copy(zone = "").missingRequiredField()!!.contains("主域名"),
        )
        assertTrue(
            config().copy(secretId = "").missingRequiredField()!!.contains("SecretId"),
        )
        assertTrue(
            config().copy(secretKey = "").missingRequiredField()!!.contains("SecretKey"),
        )
        assertNull(config().missingRequiredField())
    }

    @Test
    fun aMalformedFieldIsReportedByItsOwnCheck() {
        assertNotNull(config(zone = "example").zoneError())
        assertNotNull(config(record = "car_bay").recordError())
        assertNotNull(config(id = "short").secretIdError())
        assertNotNull(config(key = "short").secretKeyError())
        assertNull(config().zoneError())
    }

    @Test
    fun enablingRequiresEveryField() {
        assertNotNull(OwnDomainConfig(enabled = true, zone = "example.com").validate())
        assertNull(config().validate())
    }

    @Test
    fun validatesTheOptionalEmail() {
        assertNull(config(email = "driver@example.com").validate())
        assertNotNull(config(email = "not-an-email").validate())
    }
}
