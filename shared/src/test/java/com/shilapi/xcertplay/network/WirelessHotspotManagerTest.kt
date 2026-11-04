package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class WirelessHotspotManagerTest {
    @Test
    fun convertsWifiFrequencyToIap2Channel() {
        assertEquals(1, wifiFrequencyMhzToChannel(2412))
        assertEquals(6, wifiFrequencyMhzToChannel(2437))
        assertEquals(14, wifiFrequencyMhzToChannel(2484))
        assertEquals(36, wifiFrequencyMhzToChannel(5180))
        assertEquals(149, wifiFrequencyMhzToChannel(5745))
        assertEquals(177, wifiFrequencyMhzToChannel(5885))
        assertEquals(1, wifiFrequencyMhzToChannel(5955))
    }

    @Test
    fun rejectsUnsupportedOrInvalidFrequencies() {
        assertNull(wifiFrequencyMhzToChannel(0))
        assertNull(wifiFrequencyMhzToChannel(2413))
        assertNull(wifiFrequencyMhzToChannel(6001))
    }

    @Test
    fun convertsKnownChannelAndBandToFrequency() {
        assertEquals(2412, wifiChannelToFrequencyMhz(1, band = 1))
        assertEquals(5180, wifiChannelToFrequencyMhz(36, band = 2))
        assertEquals(5955, wifiChannelToFrequencyMhz(1, band = 4))
        assertNull(wifiChannelToFrequencyMhz(1, band = 3)) // Multi-band auto is not 6 GHz.
        assertNull(wifiChannelToFrequencyMhz(0, band = 2))
        assertNull(wifiChannelToFrequencyMhz(36, band = null))
    }

    @Test fun legacyHotspotBandUsesItsOwnConstants() {
        assertEquals(2412, wifiChannelToFrequencyMhz(1, legacyHotspotBandToSoftApBand(0)))
        assertEquals(5745, wifiChannelToFrequencyMhz(149, legacyHotspotBandToSoftApBand(1)))
        assertNull(wifiChannelToFrequencyMhz(1, legacyHotspotBandToSoftApBand(-1)))
        assertEquals("2.4 / 5 GHz (auto)", wifiBandLabel(3))
        assertEquals("6 GHz", wifiBandLabel(4))
        assertNull(legacyHotspotBandToSoftApBand(null))
    }

    @Test
    fun doesNotReportDesiredChannelWhenAndroidDoesNotExposeOne() {
        assertEquals(
            0,
            observedManualHotspotChannel(
                apChannel = 0,
                connectionFrequencyMHz = null,
                scanFrequencyMHz = null,
                apFrequencyMHz = null,
            ),
        )
    }

    @Test
    fun theVendorElementScriptWritesAndRestartsWifi() {
        val script = HotspotVendorElement.script()

        assertTrue("IE=dd0800a0400000020021" in script)
        assertTrue("chattr -i" in script)
        assertTrue("chattr +i" in script)
        assertTrue("chmod 444" in script)
        assertTrue("svc wifi disable" in script)
        assertTrue("svc wifi enable" in script)
        assertTrue("echo restarted" in script)
        assertTrue("echo present" in script)
        assertTrue("/data/vendor/wifi/hostapd/hostapd.conf" in script)
    }

    @Test
    fun theVendorElementReportsWhatItDid() {
        assertEquals(
            HotspotVendorElement.Result.PRESENT,
            HotspotVendorElement { _ -> "present" }.ensure(),
        )
        assertEquals(
            HotspotVendorElement.Result.RESTARTED,
            HotspotVendorElement { _ -> "vendor-element\nrestarted" }.ensure(),
        )
        assertEquals(
            HotspotVendorElement.Result.FAILED,
            HotspotVendorElement { _ -> "no-config" }.ensure(),
        )
        assertEquals(
            HotspotVendorElement.Result.FAILED,
            HotspotVendorElement { _ -> null }.ensure(),
        )
    }

    @Test
    fun recognisesAnIphonePersonalHotspotUplink() {
        assertTrue(isIphonePersonalHotspotAddress("172.20.10.4"))
        assertTrue(isIphonePersonalHotspotAddress("172.20.10.14"))
        assertTrue(isIphonePersonalHotspotAddress("172.20.10.1"))   // the phone itself
        assertFalse(isIphonePersonalHotspotAddress("172.20.10.15"))
        assertFalse(isIphonePersonalHotspotAddress("192.168.43.2"))
        assertFalse(isIphonePersonalHotspotAddress(null))
        assertFalse(isIphonePersonalHotspotAddress(""))
    }

    @Test
    fun readsTheLiveApChannelFromARootedIwProbe() {
        val observation = parseRootHotspotObservation(
            """
            __IAP2_IW__
            phy#0
                    Interface wlan1
                            type AP
                            channel 6 (2437 MHz), width: 20 MHz
            __IAP2_AP__
            """.trimIndent(),
        )

        assertEquals(6, observation?.channel)
        assertEquals(2437, observation?.frequencyMHz)
        assertEquals(true, observation?.apMode)
    }

    @Test
    fun readsSecurityTypeFromTheDumpsysFallback() {
        val observation = parseRootHotspotObservation(
            """
            __IAP2_IW__
            Interface wlan1
                    type AP
            __IAP2_AP__
              SoftApConfiguration{securityType=3, apChannel=36}
            """.trimIndent(),
        )

        assertEquals(3, observation?.securityType)
        assertEquals(36, observation?.channel)
        assertNull(observation?.frequencyMHz)
    }

    @Test
    fun anUnreadableProbeReportsNothing() {
        assertNull(parseRootHotspotObservation(""))
        assertNull(parseRootHotspotObservation("su: permission denied"))
    }

    @Test
    fun prefersTheObservedBroadcastSsid() {
        assertEquals(
            "WheelPlay_5G",
            preferredBroadcastSsid(configured = "WheelPlay", observed = "WheelPlay_5G"),
        )
        assertEquals("WheelPlay", preferredBroadcastSsid(configured = "WheelPlay", observed = null))
        assertEquals("WheelPlay", preferredBroadcastSsid(configured = "WheelPlay", observed = " "))
    }

    @Test
    fun skipsTheBroadcastSsidNoticeWhenItMatches() {
        assertNull(broadcastSsidNotice("WheelPlay", "WheelPlay"))
        assertNull(broadcastSsidNotice("WheelPlay", null))
        assertEquals(
            "Hotspot broadcasts 'WheelPlay_5G' but settings say 'WheelPlay'; " +
                "using the broadcast name",
            broadcastSsidNotice("WheelPlay", "WheelPlay_5G"),
        )
    }

    @Test
    fun prefersAnObservedChannelOverTheSavedApConfiguration() {
        // MIUI single-radio bridge: the saved AP channel can be stale after the framework retunes
        // the AP to follow the STA, so a scan result matched to the AP BSSID wins.
        assertEquals(
            149,
            observedManualHotspotChannel(
                apChannel = 6,
                connectionFrequencyMHz = null,
                scanFrequencyMHz = 5745,
                apFrequencyMHz = 2437,
            ),
        )
        assertEquals(
            6,
            observedManualHotspotChannel(
                apChannel = 36,
                connectionFrequencyMHz = 2437,
                scanFrequencyMHz = null,
                apFrequencyMHz = 5180,
            ),
        )
    }

    @Test
    fun reportsOnlyAnObservedActiveChannel() {
        assertEquals(
            36,
            observedManualHotspotChannel(
                apChannel = 36,
                connectionFrequencyMHz = null,
                scanFrequencyMHz = null,
                apFrequencyMHz = null,
            ),
        )
        assertEquals(
            44,
            observedManualHotspotChannel(
                apChannel = 0,
                connectionFrequencyMHz = 5220,
                scanFrequencyMHz = null,
                apFrequencyMHz = null,
            ),
        )
    }
}
