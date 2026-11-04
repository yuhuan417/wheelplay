package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/**
 * Reports whether the Wi-Fi STA (uplink) is associated with an upstream network.
 *
 * On the WLAN-bridge topology the same radio is both the station on the upstream network and the
 * SoftAP for the car, and the AP has to share the STA channel. Creating the SoftAP before the STA
 * association exists makes the framework retune the AP once the STA connects, which drops every
 * connected client and invalidates the channel already sent to the iPhone.
 *
 * Internet reachability is deliberately not required: the car's ConnMan probe is answered locally,
 * and the AP channel only depends on the association.
 */
object WifiUplink {
    private const val DEFAULT_POLL_MILLIS = 250L

    fun isWifiEnabled(context: Context): Boolean = runCatching {
        context.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
    }.getOrDefault(false)

    fun isAssociated(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        if (connectivity != null) {
            val associated = connectivity.allNetworks.any { network ->
                val capabilities = connectivity.getNetworkCapabilities(network) ?: return@any false
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
            if (associated) return true
        }
        // Some vendor bridges omit Wi-Fi network capabilities; fall back to the association state.
        @Suppress("DEPRECATION")
        val info = runCatching {
            context.getSystemService(WifiManager::class.java)?.connectionInfo
        }.getOrNull() ?: return false
        return info.networkId != -1 && !info.ssid.isNullOrBlank() &&
            !info.ssid.contains("unknown", ignoreCase = true)
    }

    fun awaitAssociation(
        context: Context,
        timeoutMillis: Long,
        pollMillis: Long = DEFAULT_POLL_MILLIS,
        isCancelled: () -> Boolean = { false },
    ): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        require(pollMillis > 0) { "pollMillis must be positive" }
        // System.nanoTime() is monotonic on the device and keeps advancing under Robolectric.
        val deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadlineNanos) {
            if (isCancelled()) return false
            if (isAssociated(context)) return true
            try {
                Thread.sleep(pollMillis)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return !isCancelled() && isAssociated(context)
    }

    /**
     * True when this device's own uplink is an iPhone Personal Hotspot.
     *
     * iOS always serves Personal Hotspot on 172.20.10.0/28 with 172.20.10.1 as the gateway. While
     * an iPhone is actually serving that hotspot it does not associate with the accessory AP in the
     * same band, so wireless CarPlay cannot start until either the hotspot is turned off or the head
     * unit gets another uplink.
     */
    fun isIphonePersonalHotspot(context: Context): Boolean =
        uplinkAddresses(context).any(::isIphonePersonalHotspotAddress)

    /** The station interface carrying the uplink, e.g. wlan0. */
    fun stationInterfaceName(context: Context): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: return null
        return runCatching {
            connectivity.allNetworks.firstNotNullOfOrNull { network ->
                val capabilities = connectivity.getNetworkCapabilities(network)
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                    null
                } else {
                    connectivity.getLinkProperties(network)?.interfaceName
                }
            }
        }.getOrNull()
    }

    internal fun uplinkAddresses(context: Context): List<String> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: return emptyList()
        return runCatching {
            connectivity.allNetworks.flatMap { network ->
                val capabilities = connectivity.getNetworkCapabilities(network)
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                    emptyList()
                } else {
                    connectivity.getLinkProperties(network)?.linkAddresses
                        ?.mapNotNull { it.address?.hostAddress }
                        .orEmpty()
                }
            }
        }.getOrDefault(emptyList())
    }
}

/** 172.20.10.0/28 is fixed for iOS Personal Hotspot; the device itself takes .1-.14. */
internal fun isIphonePersonalHotspotAddress(address: String?): Boolean {
    val parts = address?.split('.') ?: return false
    if (parts.size != 4) return false
    if (parts[0] != "172" || parts[1] != "20" || parts[2] != "10") return false
    return parts[3].toIntOrNull()?.let { it in 1..14 } ?: false
}
