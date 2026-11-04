package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.diagnostics.WirelessHandoffRecord
import com.shilapi.xcertplay.iap2.message.Iap2CarPlayMessages
import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.message.Iap2WirelessMessages
import com.shilapi.xcertplay.iap2.message.Iap2WirelessSessionParameters
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import kotlin.math.min

/**
 * The wireless LIVI control sequence after a CSM channel is ready:
 * Identification, MFi, the five update subscriptions, then Wi-Fi credentials and the
 * 0x4E0D/0x4E0E Wireless CarPlay transport notifications.
 *
 * The caller retains ownership of [session]. While [run] is active it is the only receiver and
 * forwards each non-control CSM frame to [onIncoming].
 */
class Iap2WirelessControlClient(
    private val session: Iap2Session,
    private val mfi: Iap2MfiAuthenticationClient,
) {
    fun run(
        identification: Iap2IdentificationConfig,
        endpoint: Iap2WirelessCarPlayEndpoint,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        onReady: () -> Unit = {},
        onIncoming: (Iap2Frame) -> Unit = {},
        onProgress: (String) -> Unit = {},
        beforeStartSession: () -> Unit = {},
        onStartSessionSent: (Long) -> Unit = {},
    ): Iap2WirelessControlResult {
        require(identification.wireless != null) {
            "Wireless control requires an Iap2IdentificationConfig with wireless transport"
        }
        require(timeoutMillis == NO_TIMEOUT_MILLIS || timeoutMillis in 1..MAX_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 1..$MAX_TIMEOUT_MILLIS or NO_TIMEOUT_MILLIS"
        }

        val deadlineNanos = if (timeoutMillis == NO_TIMEOUT_MILLIS) {
            Long.MAX_VALUE
        } else {
            deadlineAfter(timeoutMillis)
        }
        Iap2IdentificationClient(session).identify(identification, requireRemaining(deadlineNanos))
        onProgress("iap2 identification accepted")
        var stage = Iap2WirelessControlStage.IDENTIFIED

        mfi.run(session, requireRemaining(deadlineNanos), onProgress)
        stage = Iap2WirelessControlStage.AUTHENTICATED
        onProgress("iap2 authentication accepted")

        for (subscription in Iap2ControlMessages.subscriptions()) {
            send(subscription, deadlineNanos)
        }
        stage = Iap2WirelessControlStage.SUBSCRIBED
        onProgress("iap2 subscriptions sent")
        onReady()

        var forwardedFrames = 0
        var wifiConfigurationsSent = 0
        var carPlayStartSessionsSent = 0
        var preTransportWiFiConfigurationsSent = 0
        var postTransportWiFiConfigurationsSent = 0
        var transportNotificationSeen = false
        var wirelessCarPlayAvailableSeen = false
        var wirelessAvailabilityReported: Boolean? = null
        var wifiConfigurationRequests = 0
        var proactiveWifiConfigurationSent = false
        // Give the phone a moment to ask for the credentials in the normal order before pushing
        // them unsolicited; a phone that never asks (0x5702 absent) has nothing to join with.
        val proactiveWifiEligibleAtNanos = System.nanoTime() +
            PROACTIVE_WIFI_CONFIGURATION_GRACE_MILLIS * NANOS_PER_MILLISECOND
        // No "ssid"/"passphrase" tokens: the diagnostic export drops lines mentioning them.
        val hints = "name-length=${endpoint.ssid.length} key-length=${endpoint.passphrase.length} " +
            "channel=${endpoint.channel} security=${endpoint.security.name} " +
            "ip=${endpoint.ipAddresses.joinToString(",")}"
        WirelessHandoffRecord.put("iap2-config-channel", endpoint.channel)
        WirelessHandoffRecord.put("iap2-config-security", endpoint.security.name)
        while (true) {
                val remaining = remainingMillis(deadlineNanos)
                if (remaining == 0L) {
                    return Iap2WirelessControlResult(
                        Iap2WirelessControlTerminal.TIMED_OUT,
                        stage,
                        forwardedFrames,
                        wifiConfigurationsSent,
                        carPlayStartSessionsSent,
                        transportNotificationSeen,
                        postTransportWiFiConfigurationsSent,
                        wirelessCarPlayAvailableSeen,
                        wirelessAvailabilityReported,
                        wifiConfigurationRequests,
                    )
                }
                // A phone that never asked for the credentials has nothing to join with, so push
                // them once after a grace period. Checked before receiving so a chatty session
                // cannot postpone it past the deadline.
                if (
                    !proactiveWifiConfigurationSent && wifiConfigurationRequests == 0 &&
                    System.nanoTime() >= proactiveWifiEligibleAtNanos
                ) {
                    send(accessoryWiFiConfiguration(endpoint), deadlineNanos)
                    proactiveWifiConfigurationSent = true
                    preTransportWiFiConfigurationsSent++
                    wifiConfigurationsSent++
                    WirelessHandoffRecord.put("iap2-config-sent", wifiConfigurationsSent)
                    WirelessHandoffRecord.put("iap2-config-pushed", "yes")
                    stage = later(stage, Iap2WirelessControlStage.WIFI_CONFIG_SENT)
                    onProgress(
                        "iap2 tx=0x5703 proactive accessory-wifi-configuration " +
                            "(the phone sent no 0x5702 within " +
                            "$PROACTIVE_WIFI_CONFIGURATION_GRACE_MILLIS ms) $hints",
                    )
                }
                val wait = if (!proactiveWifiConfigurationSent && wifiConfigurationRequests == 0) {
                    min(remaining, PROACTIVE_WIFI_CONFIGURATION_POLL_MILLIS)
                } else {
                    remaining
                }
                val incoming = session.recv(wait)
                if (incoming == null) {
                    if (session.isClosed) {
                        return Iap2WirelessControlResult(
                            Iap2WirelessControlTerminal.CHANNEL_CLOSED,
                            stage,
                            forwardedFrames,
                            wifiConfigurationsSent,
                            carPlayStartSessionsSent,
                            transportNotificationSeen,
                            postTransportWiFiConfigurationsSent,
                            wirelessCarPlayAvailableSeen,
                            wirelessAvailabilityReported,
                            wifiConfigurationRequests,
                        )
                    }
                    if (remainingMillis(deadlineNanos) == 0L) {
                        return Iap2WirelessControlResult(
                            Iap2WirelessControlTerminal.TIMED_OUT,
                            stage,
                            forwardedFrames,
                            wifiConfigurationsSent,
                            carPlayStartSessionsSent,
                            transportNotificationSeen,
                            postTransportWiFiConfigurationsSent,
                            wirelessCarPlayAvailableSeen,
                            wirelessAvailabilityReported,
                            wifiConfigurationRequests,
                        )
                    }
                    continue
                }

                when (incoming.messageId) {
                    REQUEST_ACCESSORY_WIFI_CONFIGURATION -> {
                        wifiConfigurationRequests++
                        proactiveWifiConfigurationSent = true
                        WirelessHandoffRecord.put("iap2-credentials-requests", wifiConfigurationRequests)
                        onProgress(
                            "iap2 rx=0x5702 request-wifi-configuration " +
                                "request=${wifiConfigurationRequests}",
                        )
                        val postTransport = transportNotificationSeen
                        val sentCount = if (postTransport) {
                            postTransportWiFiConfigurationsSent
                        } else {
                            preTransportWiFiConfigurationsSent
                        }
                        val limit = if (postTransport) {
                            MAX_POST_TRANSPORT_WIFI_CONFIGURATION_SENDS
                        } else {
                            MAX_PRE_TRANSPORT_WIFI_CONFIGURATION_SENDS
                        }
                        if (sentCount >= limit) {
                            onProgress(
                                "iap2 0x5703 ignored: maximum Wi-Fi configuration sends reached",
                            )
                        } else {
                            send(accessoryWiFiConfiguration(endpoint), deadlineNanos)
                            stage = later(
                                stage,
                                if (postTransport) {
                                    Iap2WirelessControlStage.POST_TRANSPORT_WIFI_CONFIG_SENT
                                } else {
                                    Iap2WirelessControlStage.WIFI_CONFIG_SENT
                                },
                            )
                            wifiConfigurationsSent++
                            WirelessHandoffRecord.put("iap2-config-sent", wifiConfigurationsSent)
                            if (postTransport) {
                                postTransportWiFiConfigurationsSent++
                            } else {
                                preTransportWiFiConfigurationsSent++
                            }
                            onProgress("iap2 tx=0x5703 accessory-wifi-configuration $hints")
                        }
                    }

                    CARPLAY_AVAILABILITY -> {
                        val availability = runCatching {
                            Iap2WirelessMessages.carPlayAvailability(incoming)
                        }.onFailure {
                            onProgress("iap2 rx=0x4300 carplay-availability unreadable: ${it.message}")
                        }.getOrNull()
                        if (availability == null) {
                            onProgress("iap2 rx=0x4300 carplay-availability")
                        } else {
                            wirelessAvailabilityReported = availability.wirelessAvailable
                            WirelessHandoffRecord.put(
                                "iap2-wireless-available",
                                availability.wirelessAvailable,
                            )
                            WirelessHandoffRecord.put(
                                "iap2-wired-available",
                                availability.wiredAvailable,
                            )
                            WirelessHandoffRecord.put(
                                "iap2-bluetooth-id-seen",
                                availability.bluetoothIdentifier != null,
                            )
                            onProgress(
                                "iap2 rx=0x4300 carplay-availability " +
                                    "wired=${availability.wiredAvailable} " +
                                    "wireless=${availability.wirelessAvailable} " +
                                    "bluetoothIdentifier=${availability.bluetoothIdentifier ?: "none"}",
                            )
                            if (availability.wirelessRejected) {
                                // iOS only joins the accessory hotspot for a wireless CarPlay
                                // session it has been allowed to start.
                                onProgress(
                                    "iap2 warning: the iPhone reports wireless CarPlay " +
                                        "unavailable; it will not join ${hints}; check " +
                                        "iPhone Settings > General > CarPlay (and the " +
                                        "\"Enable Wireless CarPlay\" prompt) for this accessory",
                                )
                            }
                        }
                        if (wifiConfigurationRequests == 0) {
                            onProgress(
                                "iap2 warning: the iPhone never requested the Wi-Fi " +
                                    "configuration (0x5702) before CarPlayAvailability; iOS " +
                                    "decides on its own whether to join $hints",
                            )
                        }
                        beforeStartSession()
                        send(carPlayStartSession(endpoint), deadlineNanos)
                        onStartSessionSent(System.nanoTime())
                        stage = later(stage, Iap2WirelessControlStage.CARPLAY_START_SENT)
                        carPlayStartSessionsSent++
                        WirelessHandoffRecord.put("iap2-start-sessions", carPlayStartSessionsSent)
                        onProgress("iap2 tx=0x4301 carplay-start-session $hints")
                    }

                    WIRELESS_CARPLAY_UPDATE -> {
                        val available = Iap2WirelessMessages.wirelessCarPlayAvailability(incoming)
                        if (available) wirelessCarPlayAvailableSeen = true
                        WirelessHandoffRecord.put("iap2-wireless-update", available)
                        onProgress("iap2 rx=0x4e0d wireless-carplay-update available=$available")
                    }

                    DEVICE_TRANSPORT_IDENTIFIER_NOTIFICATION -> {
                        transportNotificationSeen = true
                        val identifiers = Iap2WirelessMessages.deviceTransportIdentifier(incoming)
                        stage = later(stage, Iap2WirelessControlStage.TRANSPORT_NOTIFIED)
                        onProgress(
                            "iap2 rx=0x4e0e device-transport-identifier " +
                                "bluetooth=${identifiers.bluetoothMac ?: "none"} " +
                                "usb=${identifiers.usbTransportIdentifier ?: "none"}; " +
                                "resending 0x5703",
                        )
                        if (
                            postTransportWiFiConfigurationsSent >=
                            MAX_POST_TRANSPORT_WIFI_CONFIGURATION_SENDS
                        ) {
                            onProgress(
                                "iap2 post-transport 0x5703 ignored: " +
                                    "maximum Wi-Fi configuration sends reached",
                            )
                        } else {
                            send(accessoryWiFiConfiguration(endpoint), deadlineNanos)
                            stage = later(
                                stage,
                                Iap2WirelessControlStage.POST_TRANSPORT_WIFI_CONFIG_SENT,
                            )
                            wifiConfigurationsSent++
                            WirelessHandoffRecord.put("iap2-config-sent", wifiConfigurationsSent)
                            postTransportWiFiConfigurationsSent++
                            onProgress(
                                "iap2 tx=0x5703 post-transport accessory-wifi-configuration $hints",
                            )
                        }
                    }

                    else -> {
                        onProgress("iap2 rx=0x${incoming.messageId.toString(16).padStart(4, '0')}")
                        onIncoming(incoming)
                        forwardedFrames++
                    }
                }
        }
    }

    private fun send(frame: Iap2Frame, deadlineNanos: Long) {
        session.send(frame, requireRemaining(deadlineNanos))
    }

    companion object {
        private const val REQUEST_ACCESSORY_WIFI_CONFIGURATION = 0x5702
        private const val ACCESSORY_WIFI_CONFIGURATION = 0x5703
        private const val CARPLAY_AVAILABILITY = 0x4300
        private const val CARPLAY_START_SESSION = 0x4301
        private const val WIRELESS_CARPLAY_UPDATE = 0x4e0d
        private const val DEVICE_TRANSPORT_IDENTIFIER_NOTIFICATION = 0x4e0e
        const val NO_TIMEOUT_MILLIS = Long.MAX_VALUE
        private const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        private const val MAX_TIMEOUT_MILLIS = 24 * 60 * 60 * 1_000L
        private const val MAX_RECV_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        private const val MAX_PRE_TRANSPORT_WIFI_CONFIGURATION_SENDS = 5
        private const val PROACTIVE_WIFI_CONFIGURATION_GRACE_MILLIS = 3_000L
        private const val PROACTIVE_WIFI_CONFIGURATION_POLL_MILLIS = 1_000L
        private const val MAX_POST_TRANSPORT_WIFI_CONFIGURATION_SENDS = 2
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /** Reference-compatible 0x5703 body, including the AP BSSID when it is known. */
        fun accessoryWiFiConfiguration(endpoint: Iap2WirelessCarPlayEndpoint): Iap2Frame =
            Iap2WirelessMessages.accessoryWiFiConfiguration(
                ssid = endpoint.ssid,
                passphrase = endpoint.passphrase,
                channel = endpoint.channel,
                securityType = endpoint.security.wireValue,
                bssid = endpoint.accessPointBssid,
            )

        /** Wireless 0x4301 reply carrying the receiver address, port and pairing identity. */
        fun carPlayStartSession(endpoint: Iap2WirelessCarPlayEndpoint): Iap2Frame =
            Iap2CarPlayMessages.startSession(
                wireless = Iap2WirelessSessionParameters(
                    ssid = endpoint.ssid,
                    passphrase = endpoint.passphrase,
                    channel = endpoint.channel,
                    ipAddresses = endpoint.ipAddresses,
                    securityType = endpoint.security.wireValue,
                ),
                airPlayPort = endpoint.airPlayPort,
                deviceIdentifier = endpoint.deviceIdentifier,
                publicKey = endpoint.publicKey,
                sourceVersion = endpoint.sourceVersion,
            )

        private fun later(
            current: Iap2WirelessControlStage,
            next: Iap2WirelessControlStage,
        ): Iap2WirelessControlStage = if (current.ordinal >= next.ordinal) current else next

        private fun deadlineAfter(timeoutMillis: Long): Long {
            val now = System.nanoTime()
            val delta = timeoutMillis * NANOS_PER_MILLISECOND
            return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
        }

        private fun requireRemaining(deadlineNanos: Long): Long = remainingMillis(deadlineNanos).also {
            if (it == 0L) throw Iap2TransportException.TimedOut("Timed out during wireless iAP2 control bring-up")
        }

        private fun remainingMillis(deadlineNanos: Long): Long {
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) return 0L
            return min(
                MAX_RECV_TIMEOUT_MILLIS,
                (remaining + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND,
            )
        }
    }
}

/** Wireless 0x5703 security values. */
enum class Iap2WirelessSecurity(val wireValue: Int) {
    NONE(0),
    WEP(1),
    WPA_WPA2(2),
    WPA3_TRANSITION(3),
    WPA3_ONLY(4),
}

/** Wireless hotspot and AirPlay endpoint sent in 0x5703 and 0x4301. */
class Iap2WirelessCarPlayEndpoint(
    val ssid: String,
    val passphrase: String,
    val channel: Int,
    val security: Iap2WirelessSecurity,
    ipAddresses: List<String>,
    val airPlayPort: Int,
    val deviceIdentifier: String,
    val publicKey: String,
    val sourceVersion: String,
    /** The AP's own BSSID, sent as 0x5703 parameter 0; iOS can then join that BSS directly. */
    accessPointBssid: ByteArray? = null,
) {
    val ipAddresses: List<String> = ipAddresses.toList()
    val accessPointBssid: ByteArray? = accessPointBssid?.copyOf()

    init {
        require(accessPointBssid == null || accessPointBssid.size == 6) {
            "accessPointBssid must contain six bytes"
        }
    }

    init {
        require(ssid.isNotBlank()) { "ssid is required and must not be blank" }
        require('\u0000' !in ssid) { "ssid must not contain U+0000" }
        require('\u0000' !in passphrase) { "passphrase must not contain U+0000" }
        if (security != Iap2WirelessSecurity.NONE) {
            require(passphrase.isNotEmpty()) { "passphrase is required for secured Wi-Fi" }
        }
        require(channel in 0..0xff) { "channel must be in 0..255" }
        require(ipAddresses.isNotEmpty()) { "At least one wireless IP address is required" }
        require(ipAddresses.all { it.isNotBlank() && '\u0000' !in it }) {
            "Every wireless IP address must be non-blank and must not contain U+0000"
        }
        require(airPlayPort in 1..65535) { "airPlayPort must be in 1..65535" }
        require(deviceIdentifier.isNotBlank()) { "deviceIdentifier is required and must not be blank" }
        require('\u0000' !in deviceIdentifier) { "deviceIdentifier must not contain U+0000" }
        require(publicKey.isNotEmpty()) { "publicKey is required and must not be empty" }
        require('\u0000' !in publicKey) { "publicKey must not contain U+0000" }
        require(sourceVersion.isNotEmpty()) { "sourceVersion is required and must not be empty" }
        require('\u0000' !in sourceVersion) { "sourceVersion must not contain U+0000" }
    }
}

enum class Iap2WirelessControlStage {
    IDENTIFIED,
    AUTHENTICATED,
    SUBSCRIBED,
    WIFI_CONFIG_SENT,
    CARPLAY_START_SENT,
    TRANSPORT_NOTIFIED,
    POST_TRANSPORT_WIFI_CONFIG_SENT,
}

enum class Iap2WirelessControlTerminal { CHANNEL_CLOSED, TIMED_OUT }

/** End state of the control loop only; it is not evidence of a live Wi-Fi or AirPlay session. */
data class Iap2WirelessControlResult(
    val terminal: Iap2WirelessControlTerminal,
    val stage: Iap2WirelessControlStage,
    val forwardedFrames: Int,
    val wifiConfigurationsSent: Int,
    val carPlayStartSessionsSent: Int,
    val transportNotificationSeen: Boolean,
    val postTransportWiFiConfigurationsSent: Int,
    val wirelessCarPlayAvailableSeen: Boolean,
    /**
     * `wirelessAvailable` reported in the phone's 0x4300, or null when it never sent one.
     * `false` means the phone did not offer wireless CarPlay for this session.
     */
    val wirelessAvailabilityReported: Boolean? = null,
    /** How many times the phone asked for the accessory Wi-Fi configuration (0x5702). */
    val wifiConfigurationRequests: Int = 0,
)
