package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.Closeable
import java.net.InetAddress

enum class WirelessHotspotBackend(val label: String) {
    MANUAL_HOTSPOT("Manual hotspot"),
}

/** Whether the SSID handed to the iPhone was confirmed against what the AP broadcasts. */
enum class HotspotNameCheck(val label: String) {
    /** A scan result matched to the AP interface BSSID reports the same name. */
    MATCH("match"),

    /** The AP broadcasts a different name; the broadcast one is published. */
    DIFFERS("differs"),

    /** No scan result for this BSSID (hidden AP or missing location permission). */
    UNOBSERVABLE("unobservable"),
}

/** Where the passphrase handed to the iPhone came from. */
enum class HotspotPassphraseSource(val label: String) {
    /** Read from the live SoftAP configuration on this device. */
    LIVE_AP("live AP"),

    /** The value saved in WheelPlay settings; the live one could not be read. */
    SAVED_SETTINGS("saved settings"),
}

/** The live Wi-Fi credentials and interface details for one wireless CarPlay hotspot. */
class WirelessHotspotInfo(
    val ssid: String,
    val passphrase: String,
    val security: Iap2WirelessSecurity,
    val channel: Int,
    val frequencyMHz: Int?,
    val bssid: String?,
    val interfaceName: String?,
    val hostAddress: InetAddress?,
    val bandLabel: String,
    val backend: WirelessHotspotBackend,
    /** Live AP passphrase when readable; otherwise the saved setting. */
    val passphraseSource: HotspotPassphraseSource,
    /** A hidden SSID never auto-joins on iOS, so the user must unhide the hotspot. */
    val hiddenSsid: Boolean,
    /** One actionable credential problem for the UI, or null when nothing is known to be wrong. */
    val notice: String?,
    /** Whether the published SSID was confirmed against the AP's own beacon. */
    val nameCheck: HotspotNameCheck,
    /** True when the rooted probe saw the interface in AP mode; null when it was not observed. */
    val apMode: Boolean?,
) {
    override fun toString(): String =
        "WirelessHotspotInfo(backend=${backend.label}, ssid='$ssid', " +
            "passphrase=<redacted> source=${passphraseSource.label}, security=$security, " +
            "channel=$channel, frequencyMHz=$frequencyMHz, bssid='$bssid', " +
            "hiddenSsid=$hiddenSsid, notice=$notice, interfaceName=$interfaceName, " +
            "hostAddress=$hostAddress, bandLabel='$bandLabel')"
}

/** Owns one Android Wi-Fi group and all resources needed to keep it alive. */
interface WirelessHotspotManager : Closeable {
    /**
     * Starts a hotspot and waits up to [timeoutMillis] for its live configuration and AP
     * interface. Implementations must not be called on the main thread.
     */
    fun start(timeoutMillis: Long): WirelessHotspotInfo

    /** Recheck the selected AP immediately before advertising it to the phone. */
    fun validateReady() {}

    /** The authenticated wireless session has rendered CarPlay; AP creation alone is insufficient. */
    fun onCarPlayConfirmed() {}
}

/** Converts an IEEE 802.11 frequency in MHz to its channel number. */
internal fun wifiFrequencyMhzToChannel(frequencyMHz: Int): Int? = when {
    frequencyMHz in 2412..2472 && (frequencyMHz - 2407) % 5 == 0 ->
        (frequencyMHz - 2407) / 5
    frequencyMHz == 2484 -> 14
    frequencyMHz in 5160..5895 && (frequencyMHz - 5000) % 5 == 0 ->
        (frequencyMHz - 5000) / 5
    frequencyMHz in 5955..7115 && (frequencyMHz - 5950) % 5 == 0 ->
        (frequencyMHz - 5950) / 5
    else -> null
}

/** Converts an IEEE 802.11 channel number to MHz when its band is known. */
internal fun wifiChannelToFrequencyMhz(channel: Int, band: Int? = null): Int? = when {
    channel == 0 -> null
    band == 1 && channel in 1..13 -> 2407 + channel * 5
    band == 1 && channel == 14 -> 2484
    band == 2 && channel in 32..177 -> 5000 + channel * 5
    band == 4 && channel in 1..233 -> 5950 + channel * 5
    else -> null
}

/** Maps Android's [android.net.wifi.SoftApConfiguration] band constants to a user-facing label. */
internal fun wifiBandLabel(band: Int?): String? = when (band) {
    1 -> "2.4 GHz"
    2 -> "5 GHz"
    3 -> "2.4 / 5 GHz (auto)"
    4 -> "6 GHz"
    else -> null
}

/** WifiConfiguration uses 0/1/-1; SoftApConfiguration uses a 1/2/4 bit mask. */
internal fun legacyHotspotBandToSoftApBand(band: Int?): Int? = when (band) {
    0 -> 1
    1 -> 2
    -1 -> 3
    else -> null
}

/**
 * The SSID the phone is told to join: the broadcast one when it can be observed, because a saved
 * name can drift from what the AP actually advertises (MIUI bridge hotspots are known to add a
 * band suffix such as `_5G`).
 */
internal fun preferredBroadcastSsid(configured: String, observed: String?): String =
    observed?.takeIf { it.isNotBlank() } ?: configured

/** A user-visible warning when the saved SSID differs from what the AP broadcasts. */
internal fun broadcastSsidNotice(configured: String, observed: String?): String? =
    when (broadcastNameCheck(configured, observed)) {
        HotspotNameCheck.DIFFERS ->
            "Hotspot broadcasts '$observed' but settings say '$configured'; using the broadcast name"
        HotspotNameCheck.MATCH, HotspotNameCheck.UNOBSERVABLE -> null
    }

/** Compares the saved name with the AP's own beacon, if one was observed. */
internal fun broadcastNameCheck(configured: String, observed: String?): HotspotNameCheck {
    val broadcast = observed?.takeIf { it.isNotBlank() } ?: return HotspotNameCheck.UNOBSERVABLE
    return if (broadcast == configured) HotspotNameCheck.MATCH else HotspotNameCheck.DIFFERS
}

/**
 * Picks the channel sent to the iPhone in 0x5703/0x4301.
 *
 * Observed channels win over the saved SoftAP configuration: on single-radio devices (MIUI
 * "WLAN 信号桥") the framework retunes the AP to follow the STA, so the saved channel can be stale
 * while the radio is already on another one, and a stale hint makes the phone scan the wrong
 * channel. Only when nothing better is known does the configured value remain, and an entirely
 * unknown channel stays 0 ("auto") instead of echoing a guess.
 */
internal fun observedManualHotspotChannel(
    apChannel: Int,
    connectionFrequencyMHz: Int?,
    scanFrequencyMHz: Int?,
    apFrequencyMHz: Int?,
): Int {
    // A scan result matched to the AP interface BSSID is the only direct beacon observation.
    scanFrequencyMHz?.let(::wifiFrequencyMhzToChannel)?.let { return it }
    // On a single-radio bridge the AP follows the STA, and both are observed live.
    connectionFrequencyMHz?.let(::wifiFrequencyMhzToChannel)?.let { return it }
    // Values below only restate the saved SoftAP configuration.
    apFrequencyMHz?.let(::wifiFrequencyMhzToChannel)?.let { return it }
    if (apChannel > 0) return apChannel
    return 0
}
