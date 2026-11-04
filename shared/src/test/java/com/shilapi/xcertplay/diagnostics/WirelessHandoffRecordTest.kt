package com.shilapi.xcertplay.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessHandoffRecordTest {
    @After
    fun tearDown() = WirelessHandoffRecord.reset()

    @Test
    fun reportIsEmptyBeforeAnAttempt() {
        WirelessHandoffRecord.reset()

        assertEquals("no wireless join attempt recorded", WirelessHandoffRecord.report())
    }

    @Test
    fun reportPrintsTheRecordedFactsAndTheDerivedNextStep() {
        WirelessHandoffRecord.reset()
        WirelessHandoffRecord.put("hotspot-security", "WPA3_ONLY")
        WirelessHandoffRecord.put("iap2-credentials-requests", 1)
        WirelessHandoffRecord.result("iphone-did-not-join")

        val report = WirelessHandoffRecord.report()

        assertTrue(report.lines().first() == "result=iphone-did-not-join")
        assertTrue("iap2-credentials-requests=1" in report)
        assertTrue("hotspot-security=WPA3_ONLY" in report)
        assertTrue(
            "next=the hotspot is WPA3-only; use WPA2 or WPA3 transition" in report,
        )
    }

    @Test
    fun unknownFactsAreDroppedAndTheNewestAttemptWins() {
        WirelessHandoffRecord.reset()
        WirelessHandoffRecord.put("a", 1)
        WirelessHandoffRecord.put("a", null)

        assertTrue(WirelessHandoffRecord.isEmpty())

        WirelessHandoffRecord.put("b", true)
        assertFalse(WirelessHandoffRecord.isEmpty())
        assertFalse("a=" in WirelessHandoffRecord.report())
    }

    @Test
    fun missingWirelessAvailabilityTakesPriorityOverOtherHints() {
        assertEquals(
            "the iPhone did not offer wireless CarPlay for this accessory; check iPhone " +
                "Settings > General > CarPlay and the Enable Wireless CarPlay prompt",
            suggestNext(mapOf("iap2-wireless-available" to "false")),
        )
    }

    @Test
    fun aSessionThatNeverAskedForCredentialsSaysSo() {
        val next = suggestNext(mapOf("iap2-credentials-requests" to "0"))

        assertTrue("never asked for the hotspot credentials" in next)
        assertTrue("credentials are not the cause" in next)
    }

    @Test
    fun anAskedJoinFallsBackToTheIosSideChecks() {
        val next = suggestNext(
            mapOf(
                "iap2-wireless-available" to "true",
                "iap2-credentials-requests" to "1",
                "iap2-config-sent" to "1",
            ),
        )

        assertTrue("forget this network and retry" in next)
    }

    @Test
    fun summaryLineStaysOneLineAndFreeOfCredentials() {
        WirelessHandoffRecord.reset()
        WirelessHandoffRecord.put("iap2-credentials-requests", 2)
        WirelessHandoffRecord.result("joined")

        val line = WirelessHandoffRecord.summaryLine()

        assertFalse('\n' in line)
        assertTrue(line.startsWith("wireless join: result=joined"))
        assertTrue("iap2-credentials-requests=2" in line)
    }
}
