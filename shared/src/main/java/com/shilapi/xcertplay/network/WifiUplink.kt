package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock

/**
 * Reports whether the Wi-Fi STA (uplink) is actually connected.
 *
 * On the WLAN-bridge topology the same radio is both the station on the upstream network and the
 * SoftAP for the car. Starting the SoftAP before the STA association exists leaves clients with a
 * hotspot that has no internet, which Tesla and iOS may reject or leave.
 */
object WifiUplink {
    private const val DEFAULT_POLL_MILLIS = 250L

    fun isWifiEnabled(context: Context): Boolean = runCatching {
        context.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
    }.getOrDefault(false)

    fun isReady(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        if (connectivity != null) {
            val connected = connectivity.allNetworks.any { network ->
                val capabilities = connectivity.getNetworkCapabilities(network) ?: return@any false
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            if (connected) return true
        }
        // Some vendor bridges omit Wi-Fi network capabilities; fall back to an association check.
        @Suppress("DEPRECATION")
        val info = runCatching {
            context.getSystemService(WifiManager::class.java)?.connectionInfo
        }.getOrNull() ?: return false
        return info.networkId != -1 && !info.ssid.isNullOrBlank() &&
            !info.ssid.contains("unknown", ignoreCase = true)
    }

    fun await(
        context: Context,
        timeoutMillis: Long,
        pollMillis: Long = DEFAULT_POLL_MILLIS,
        isCancelled: () -> Boolean = { false },
    ): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        require(pollMillis > 0) { "pollMillis must be positive" }
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isCancelled()) return false
            if (isReady(context)) return true
            try {
                Thread.sleep(pollMillis)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return !isCancelled() && isReady(context)
    }
}
