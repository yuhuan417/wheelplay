package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.Iap2IdentificationConfig

enum class ManualHotspotBand {
    AUTO,
    GHZ_2_4,
    GHZ_5,
}

enum class ManualHotspotSecurity {
    OPEN,
    WPA2,
    WPA3_TRANSITION,
    WPA3,
}

/** Deployment-owned constants for one head unit. */
class CarPlayRuntimeConfig(
    val identification: Iap2IdentificationConfig,
    val manualHotspotSsid: String? = null,
    val manualHotspotPassphrase: String? = null,
    val manualHotspotBand: ManualHotspotBand = ManualHotspotBand.AUTO,
    val manualHotspotChannel: Int = 0,
    val manualHotspotSecurity: ManualHotspotSecurity = ManualHotspotSecurity.WPA2,
    val wirelessBluetoothDeviceAddress: String? = null,
) {
    init {
        val ssid = manualHotspotSsid
        require(!ssid.isNullOrBlank()) {
            "manualHotspotSsid is required"
        }
        require('\u0000' !in ssid) {
            "manualHotspotSsid must not contain U+0000"
        }
        val passphrase = manualHotspotPassphrase.orEmpty()
        require('\u0000' !in passphrase) {
            "manualHotspotPassphrase must not contain U+0000"
        }
        require(passphrase.isEmpty() || passphrase.length in 8..63) {
            "manualHotspotPassphrase must be empty or between 8 and 63 characters"
        }
        require(manualHotspotChannel in 0..196) {
            "manualHotspotChannel must be 0 or in 1..196"
        }
        require(
            manualHotspotSecurity == ManualHotspotSecurity.OPEN ||
                passphrase.length in 8..63,
        ) {
            "A passphrase between 8 and 63 characters is required for secured manual hotspots"
        }
        require(
            manualHotspotChannel == 0 ||
                isManualHotspotChannelCompatible(manualHotspotBand, manualHotspotChannel),
        ) {
            "manualHotspotChannel is not valid for the selected manual hotspot band"
        }
        require(
            manualHotspotSecurity == ManualHotspotSecurity.OPEN || passphrase.isNotEmpty(),
        ) {
            "manualHotspotPassphrase is required for secured manual hotspots"
        }
    }
}

fun isManualHotspotChannelCompatible(band: ManualHotspotBand, channel: Int): Boolean = when (band) {
    ManualHotspotBand.AUTO -> channel in 1..196
    ManualHotspotBand.GHZ_2_4 -> channel in 1..14
    ManualHotspotBand.GHZ_5 -> channel in 32..177
}
