package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * Attaches to a hotspot that is already running on this device.
 *
 * The hotspot remains owned by the system. This manager only locates its interface and reads the
 * channel/security data that the public Android APIs expose. Some vendors hide the current SoftAP
 * configuration, in which case the caller-supplied credentials remain authoritative and the iAP2
 * channel is reported as zero ("auto").
 */
class ManualHotspotManager(
    context: Context,
    ssid: String,
    passphrase: String,
    band: ManualHotspotBand,
    channel: Int,
    security: ManualHotspotSecurity,
    private val onDiagnostic: (String) -> Unit = {},
    private val rootProbe: HotspotRootProbe = HotspotRootProbe(),
) : WirelessHotspotManager {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
        ?: throw IllegalStateException("WifiManager is unavailable")
    private val interfaces: ManualHotspotInterfaces
    private var confirmed: HotspotSelection? = null
    private val expectedSsid = ssid
    private val passphrase = passphrase
    private val expectedBand = band
    private val expectedChannel = channel
    private val expectedSecurity = security.toIap2Security()

    @Volatile
    private var closed = false

    init {
        require(expectedSsid.isNotBlank()) { "ssid must not be blank" }
        require('\u0000' !in expectedSsid) { "ssid must not contain U+0000" }
        require('\u0000' !in passphrase) { "passphrase must not contain U+0000" }
        require(passphrase.isEmpty() || passphrase.length in 8..63) {
            "passphrase must be empty or between 8 and 63 characters"
        }
        require(channel in 0..196) { "channel must be 0 or in 1..196" }
        require(expectedSecurity == Iap2WirelessSecurity.NONE || passphrase.isNotEmpty()) {
            "passphrase is required for secured manual hotspots"
        }
        require(expectedSecurity != Iap2WirelessSecurity.NONE || passphrase.isEmpty()) {
            "passphrase must be empty for open manual hotspots"
        }
        interfaces = ManualHotspotInterfaces(appContext, onDiagnostic)
    }

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ManualHotspotManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val apConfiguration = readApConfiguration()
        if (apConfiguration != null && apConfiguration.ssid != expectedSsid) {
            throw WirelessStartupException(
                WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                "Manual hotspot SSID does not match the active local AP configuration: " +
                    "'${apConfiguration.ssid}'",
            )
        }
        validateApConfiguration(apConfiguration)

        val selected = ManualHotspotReadiness(
            sample = interfaces::sample,
            cancelled = { closed },
            pause = { millis -> sleep(TimeUnit.MILLISECONDS.toNanos(millis)) },
        ).await(timeoutMillis)
        confirmed = selected
        val localInterface = LocalHotspotInterface(
            selected.name,
            selected.address,
            runCatching {
                NetworkInterface.getByName(selected.name)?.hardwareAddress?.toMacAddressString()
            }.getOrNull(),
        )
        val connectionFrequency = frequencyFromConnectionInfo()
        // The scan result matched to the AP interface BSSID is the only direct observation of what
        // the iPhone will actually see: its broadcast name and its channel.
        val scan = observeScanResult(localInterface)
        // MIUI hides SoftApConfiguration from apps, so without a scan the channel and the security
        // type would be guesses. The device is rooted, so read the live AP from the system.
        val rootObservation = if (apConfiguration == null) {
            rootProbe.probe(localInterface.name)?.also { probe ->
                onDiagnostic(
                    "Manual hotspot root probe channel=${probe.channel ?: "unknown"} " +
                        "frequency=${probe.frequencyMHz?.toString() ?: "unknown"}MHz " +
                        "ap-mode=${probe.apMode ?: "unknown"} " +
                        "securityType=${probe.securityType ?: "unknown"}",
                )
            }
        } else {
            null
        }
        val scanFrequency = scan.frequencyMHz ?: rootObservation?.frequencyMHz
        val channel = observedManualHotspotChannel(
            apChannel = apConfiguration?.channel ?: rootObservation?.channel ?: 0,
            connectionFrequencyMHz = connectionFrequency,
            scanFrequencyMHz = scanFrequency,
            apFrequencyMHz = apConfiguration?.frequencyMHz,
        )
        val channelSource = when {
            apConfiguration != null && apConfiguration.channel > 0 -> "config"
            scan.frequencyMHz != null -> "scan"
            rootObservation?.channel != null -> "root"
            connectionFrequency != null -> "sta"
            else -> "none"
        }
        val frequencyMHz = when {
            apConfiguration?.frequencyMHz != null -> apConfiguration.frequencyMHz
            rootObservation?.frequencyMHz != null -> rootObservation.frequencyMHz
            connectionFrequency != null -> connectionFrequency
            scan.frequencyMHz != null -> scan.frequencyMHz
            else -> null
        }
        val broadcastSsid = preferredBroadcastSsid(expectedSsid, scan.ssid)
        val nameCheck = broadcastNameCheck(expectedSsid, scan.ssid)
        val rootSecurity = rootObservation?.securityType?.let(::mapSoftApSecurity)
        val security = apConfiguration?.security ?: rootSecurity ?: expectedSecurity
        val securitySource = when {
            apConfiguration?.security != null -> "config"
            rootSecurity != null -> "root"
            else -> "saved settings"
        }
        // The AP's own passphrase is authoritative. A saved setting that has drifted from the
        // phone's hotspot password makes iOS fail the join silently (and remember the bad
        // password for that SSID), while a manual join with the right password still works.
        val livePassphrase = usableLivePassphrase(apConfiguration, security)
        val publishedPassphrase = when {
            security == Iap2WirelessSecurity.NONE -> ""
            livePassphrase != null -> livePassphrase
            else -> passphrase
        }
        val passphraseSource = if (security != Iap2WirelessSecurity.NONE && livePassphrase != null) {
            HotspotPassphraseSource.LIVE_AP
        } else {
            HotspotPassphraseSource.SAVED_SETTINGS
        }
        val hiddenSsid = apConfiguration?.hiddenSsid == true
        // Keys avoid "ssid"/"passphrase": the diagnostic export drops any line mentioning them.
        onDiagnostic("Manual hotspot configReadable=${apConfiguration != null} " +
            "security=$security security-source=$securitySource " +
            "channelKnown=${channel > 0} channel-source=$channelSource " +
            "key-source=${passphraseSource.label} hidden=$hiddenSsid " +
            "name-check=${nameCheck.label} " +
            "hardwareAddressKnown=${localInterface.hardwareAddress != null} iface=${localInterface.name} " +
            "family=${if (localInterface.hostAddress is Inet6Address) "IPv6" else "IPv4"}")
        if (security != Iap2WirelessSecurity.NONE && publishedPassphrase.isEmpty()) {
            throw IOException("Manual hotspot is secured but no passphrase was provided")
        }
        broadcastSsidNotice(expectedSsid, scan.ssid)?.let(onDiagnostic)
        if (passphraseSource == HotspotPassphraseSource.SAVED_SETTINGS &&
            security != Iap2WirelessSecurity.NONE
        ) {
            onDiagnostic(
                "Manual hotspot key is not readable from Android; sending the saved setting. " +
                    "It must match the phone hotspot key exactly or the iPhone will not join " +
                    "automatically",
            )
        }
        if (livePassphrase != null && livePassphrase != passphrase) {
            onDiagnostic(
                "Saved hotspot key differs from the live AP key; sending the live one",
            )
        }
        if (hiddenSsid) {
            onDiagnostic(
                "Manual hotspot hides its name; iOS never auto-joins a hidden network. " +
                    "Unhide the hotspot for Bluetooth-initiated CarPlay",
            )
        }
        if (security == Iap2WirelessSecurity.WPA3_ONLY) {
            onDiagnostic(
                "Manual hotspot is WPA3-only; iOS may refuse an accessory-provided WPA3 network. " +
                    "Use WPA2 or WPA3 transition for Bluetooth-initiated CarPlay",
            )
        }
        // One actionable line for the UI; the details stay in the debug log above.
        val notice = listOfNotNull(
            broadcastSsidNotice(expectedSsid, scan.ssid),
            if (channel == 0) {
                "Hotspot channel is unknown to Android, so the iPhone gets 0 (auto)"
            } else {
                null
            },
            "Hotspot interface is not in AP mode; turn the hotspot on"
                .takeIf { rootObservation?.apMode == false },
            "Hidden hotspot: the iPhone will not join it automatically; unhide the hotspot"
                .takeIf { hiddenSsid },
            "Hotspot password is not readable by Android; it must match the phone exactly"
                .takeIf {
                    security != Iap2WirelessSecurity.NONE &&
                        passphraseSource == HotspotPassphraseSource.SAVED_SETTINGS
                },
            "Hotspot is WPA3-only; iOS may refuse it. Use WPA2 or WPA3 transition"
                .takeIf { security == Iap2WirelessSecurity.WPA3_ONLY },
        ).firstOrNull()

        if (channel == 0) {
            Log.w(
                TAG,
                "Could not read the active hotspot channel from Android public APIs; " +
                    "reporting iAP2 channel 0 (auto) instead of configured channel " +
                    "$expectedChannel",
            )
        }
        val observedBandLabel = wifiBandLabel(apConfiguration?.band)
        return WirelessHotspotInfo(
            ssid = broadcastSsid,
            passphrase = publishedPassphrase,
            security = security,
            channel = channel,
            frequencyMHz = frequencyMHz,
            bssid = localInterface.hardwareAddress,
            interfaceName = localInterface.name,
            hostAddress = localInterface.hostAddress,
            bandLabel = when (expectedBand) {
                ManualHotspotBand.GHZ_2_4 -> "2.4 GHz"
                ManualHotspotBand.GHZ_5 -> "5 GHz"
                ManualHotspotBand.AUTO ->
                    frequencyMHz?.let(::bandLabel) ?: observedBandLabel ?: "Auto"
            },
            backend = WirelessHotspotBackend.MANUAL_HOTSPOT,
            passphraseSource = passphraseSource,
            hiddenSsid = hiddenSsid,
            notice = notice,
            nameCheck = nameCheck,
            apMode = rootObservation?.apMode,
        )
    }

    /**
     * The live AP passphrase, or null when Android does not expose a usable one. OEM builds may
     * redact it (null, empty or asterisks) for third-party callers, in which case the saved
     * setting stays authoritative.
     */
    private fun usableLivePassphrase(
        configuration: ManualApConfiguration?,
        security: Iap2WirelessSecurity,
    ): String? {
        if (security == Iap2WirelessSecurity.NONE) return null
        val candidate = configuration?.passphrase ?: return null
        if (candidate.length !in 8..63) return null
        if ('\u0000' in candidate) return null
        if (candidate.all { it == '*' }) return null
        return candidate
    }

    override fun close() {
        closed = true
        interfaces.close()
    }

    private fun validateApConfiguration(configuration: ManualApConfiguration?) {
        configuration ?: return
        if (expectedChannel > 0 && configuration.channel > 0 &&
            configuration.channel != expectedChannel
        ) {
            throw WirelessStartupException(
                WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                "Manual hotspot channel ${configuration.channel} does not match configured " +
                    "channel $expectedChannel",
            )
        }
        val actualBand = when (configuration.band) {
            1 -> ManualHotspotBand.GHZ_2_4
            2 -> ManualHotspotBand.GHZ_5
            else -> null
        }
        if (actualBand != null && expectedBand != ManualHotspotBand.AUTO &&
            actualBand != expectedBand
        ) {
            throw WirelessStartupException(
                WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                "Manual hotspot band ${wifiBandLabel(configuration.band)} does not match " +
                    "configured band ${wifiBandLabel(if (expectedBand == ManualHotspotBand.GHZ_2_4) 1 else 2)}",
            )
        }
        // WPA2 vs WPA3 variants are fine: the live security is what the iPhone is told (see start()).
        // Only an open/secured mismatch means the saved password cannot be right.
        if ((configuration.security == Iap2WirelessSecurity.NONE) != (expectedSecurity == Iap2WirelessSecurity.NONE)) {
            throw WirelessStartupException(
                WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                "Manual hotspot security ${configuration.security} does not match configured " +
                    "security $expectedSecurity",
            )
        }
        val frequency = configuration.frequencyMHz ?: return
        when (expectedBand) {
            ManualHotspotBand.GHZ_2_4 -> if (frequency !in 2_400..2_500) {
                throw IOException("Manual hotspot is not running on 2.4 GHz")
            }
            ManualHotspotBand.GHZ_5 -> if (frequency !in 5_150..5_895) {
                throw IOException("Manual hotspot is not running on 5 GHz")
            }
            ManualHotspotBand.AUTO -> Unit
        }
    }

    override fun validateReady() {
        val previous = confirmed ?: throw IOException("Manual hotspot is not ready")
        val current = selectHotspotInterface(interfaces.sample()) {}
        if (closed || current == null || !previous.sameAddress(current)) {
            throw WirelessStartupException(
                WirelessStartupFailure.HOTSPOT_NOT_READY,
                "Manual hotspot interface changed before publication",
            )
        }
        val configuration = readApConfiguration()
        if (configuration != null && configuration.ssid != expectedSsid) {
            throw WirelessStartupException(
                WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                "Manual hotspot SSID changed before publication",
            )
        }
        validateApConfiguration(configuration)
    }

    private fun frequencyFromConnectionInfo(): Int? {
        val connectionInfo = try {
            wifiManager.connectionInfo
        } catch (_: SecurityException) {
            null
        } ?: return null
        if (unquote(connectionInfo.ssid) != expectedSsid) return null
        return connectionInfo.frequency.takeIf { it > 0 }
    }

    /**
     * What the AP looks like over the air, matched by the AP interface's BSSID. Without precise
     * location Android does not hand out scan results, and a hidden SSID never appears at all, so
     * a miss simply means "unobservable" rather than "wrong".
     */
    private fun observeScanResult(localInterface: LocalHotspotInterface): ScanObservation {
        val localBssid = localInterface.hardwareAddress ?: return ScanObservation(null, null)
        val scanResults = try {
            wifiManager.scanResults
        } catch (_: SecurityException) {
            return ScanObservation(null, null)
        }
        val match = scanResults.firstOrNull { result ->
            result.BSSID.equals(localBssid, ignoreCase = true)
        } ?: return ScanObservation(null, null)
        return ScanObservation(
            ssid = match.SSID?.takeIf { it.isNotBlank() },
            frequencyMHz = match.frequency.takeIf { it > 0 },
        )
    }

    @SuppressLint("PrivateApi")
    private fun readApConfiguration(): ManualApConfiguration? =
        readSoftApConfiguration() ?: readLegacyApConfiguration()

    @SuppressLint("PrivateApi")
    private fun readSoftApConfiguration(): ManualApConfiguration? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val method = WifiManager::class.java.getMethod("getSoftApConfiguration")
            val configuration = method.invoke(wifiManager) as? SoftApConfiguration
                ?: return null
            val ssid = configuration.ssid ?: return null
            val bandAndChannel = when {
                Build.VERSION.SDK_INT >= 36 -> {
                    val channels = configuration.channels
                    if (channels.size() == 0) null else channels.keyAt(0) to channels.valueAt(0)
                }
                else -> {
                    val band = (
                        SoftApConfiguration::class.java
                            .getMethod("getBand")
                            .invoke(configuration) as? Number
                        )?.toInt()
                    val channel = SoftApConfiguration::class.java
                        .getMethod("getChannel")
                        .invoke(configuration) as? Number
                    if (band == null || channel == null) null else band to channel.toInt()
                }
            }
            val band = bandAndChannel?.first
            val channel = bandAndChannel?.second ?: 0
            ManualApConfiguration(
                ssid = ssid,
                band = band,
                channel = channel,
                frequencyMHz = wifiChannelToFrequencyMhz(channel, band),
                security = mapSoftApSecurity(configuration.securityType),
                passphrase = runCatching { configuration.passphrase }.getOrNull(),
                hiddenSsid = runCatching { configuration.isHiddenSsid }.getOrNull(),
            )
        } catch (_: Throwable) {
            null
        }
    }

    @SuppressLint("PrivateApi")
    private fun readLegacyApConfiguration(): ManualApConfiguration? {
        return try {
            val method = WifiManager::class.java.getMethod("getWifiApConfiguration")
            val configuration = method.invoke(wifiManager) as? WifiConfiguration
                ?: return null
            val ssid = unquote(configuration.SSID) ?: return null
            val channel = try {
                WifiConfiguration::class.java.getField("apChannel").getInt(configuration)
            } catch (_: ReflectiveOperationException) {
                0
            }
            val band = try {
                legacyHotspotBandToSoftApBand(WifiConfiguration::class.java.getField("apBand").getInt(configuration))
            } catch (_: ReflectiveOperationException) {
                null
            }
            ManualApConfiguration(
                ssid = ssid,
                band = band,
                channel = channel,
                frequencyMHz = wifiChannelToFrequencyMhz(channel, band),
                security = mapWifiConfigurationSecurity(configuration),
                passphrase = configuration.preSharedKey?.let(::unquote),
                hiddenSsid = runCatching {
                    WifiConfiguration::class.java
                        .getField("hiddenSSID")
                        .getBoolean(configuration)
                }.getOrNull(),
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun mapSoftApSecurity(securityType: Int): Iap2WirelessSecurity = when (securityType) {
        SoftApConfiguration.SECURITY_TYPE_OPEN -> Iap2WirelessSecurity.NONE
        SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> Iap2WirelessSecurity.WPA_WPA2
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION ->
            Iap2WirelessSecurity.WPA3_TRANSITION
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> Iap2WirelessSecurity.WPA3_ONLY
        else -> Iap2WirelessSecurity.WPA_WPA2
    }

    private fun mapWifiConfigurationSecurity(
        configuration: WifiConfiguration,
    ): Iap2WirelessSecurity {
        val keyManagement = configuration.allowedKeyManagement ?: return Iap2WirelessSecurity.NONE
        val open = keyManagement.get(WifiConfiguration.KeyMgmt.NONE)
        val wpa2 = keyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK)
        val sae = keyManagement.get(WifiConfiguration.KeyMgmt.SAE)
        return when {
            open && !wpa2 && !sae -> Iap2WirelessSecurity.NONE
            wpa2 && sae -> Iap2WirelessSecurity.WPA3_TRANSITION
            wpa2 -> Iap2WirelessSecurity.WPA_WPA2
            sae -> Iap2WirelessSecurity.WPA3_ONLY
            else -> Iap2WirelessSecurity.WPA_WPA2
        }
    }

    private fun bandLabel(frequencyMHz: Int): String = when (frequencyMHz) {
        in 2400..2500 -> "2.4 GHz"
        in 5150..5895 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        else -> "Unknown band"
    }

    private fun unquote(value: String?): String? {
        if (value == null) return null
        return if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }

    private fun ByteArray.toMacAddressString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun sleep(nanos: Long) {
        try {
            TimeUnit.NANOSECONDS.sleep(nanos)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for the manual hotspot", interrupted)
        }
    }

    private class ManualApConfiguration(
        val ssid: String,
        val band: Int?,
        val channel: Int,
        val frequencyMHz: Int?,
        val security: Iap2WirelessSecurity,
        /** Live passphrase when Android exposes it to this app. */
        val passphrase: String? = null,
        /** Null when the platform does not report it. */
        val hiddenSsid: Boolean? = null,
    )

    private class ScanObservation(
        val ssid: String?,
        val frequencyMHz: Int?,
    )

    private class LocalHotspotInterface(
        val name: String,
        val hostAddress: InetAddress,
        val hardwareAddress: String?,
    )

    private companion object {
        const val TAG = "xcertplay-usb"
    }
}

private fun ManualHotspotSecurity.toIap2Security(): Iap2WirelessSecurity = when (this) {
    ManualHotspotSecurity.OPEN -> Iap2WirelessSecurity.NONE
    ManualHotspotSecurity.WPA2 -> Iap2WirelessSecurity.WPA_WPA2
    ManualHotspotSecurity.WPA3_TRANSITION -> Iap2WirelessSecurity.WPA3_TRANSITION
    ManualHotspotSecurity.WPA3 -> Iap2WirelessSecurity.WPA3_ONLY
}
