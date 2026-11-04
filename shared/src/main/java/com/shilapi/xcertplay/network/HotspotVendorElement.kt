package com.shilapi.xcertplay.network

import java.util.concurrent.TimeUnit

/**
 * Makes the SoftAP advertise Apple's vendor element.
 *
 * Without it an iPhone can receive perfect credentials and still decline to join: Apple's wireless
 * CarPlay setup expects "Apple Device" / interworking information in the AP's beacons, and the
 * public LIVI implementation advertises `dd0800a0400000020021` (ID 221, OUI 00:a0:40, type 0).
 *
 * The element only reaches the beacon when hostapd builds the interface, so this rewrites the
 * hostapd config and restarts Wi-Fi (which brings the system hotspot back up on this ROM) before
 * the iPhone is asked to join. The file is immutable on MIUI, hence the `chattr` handling.
 *
 * Requires root; the caller runs it on a worker thread and before any CarPlay session exists.
 */
class HotspotVendorElement(
    private val execute: (String) -> String? = ::defaultRootExecute,
) {
    enum class Result {
        /** The element was already configured; nothing was restarted. */
        PRESENT,

        /** The element was written and Wi-Fi was restarted so hostapd publishes it. */
        RESTARTED,

        /** Root, the config file or the element could not be handled. */
        FAILED,
    }

    /**
     * @param forceRestart recreate the AP even when the element is already configured. iOS only
     *   seems to auto-join a BSS it has not been serving next to, so the AP is rebuilt right
     *   before the iPhone is asked to join whenever it is currently serving this device's uplink.
     */
    fun ensure(forceRestart: Boolean = false): Result =
        runCatching { execute(script(forceRestart)) }.getOrNull()?.let { output ->
            when {
                "restarted" in output -> Result.RESTARTED
                "present" in output -> Result.PRESENT
                else -> Result.FAILED
            }
        } ?: Result.FAILED

    companion object {
        /** Apple vendor element used by the public LIVI wireless CarPlay implementation. */
        const val ELEMENT = "dd0800a0400000020021"

        private const val CONFIG_PATH = "/data/vendor/wifi/hostapd/hostapd.conf"

        /**
         * One short transaction: add the element when missing, then restart Wi-Fi so hostapd
         * rebuilds the interface. Prints `present` or `restarted` for the caller.
         */
        internal fun script(forceRestart: Boolean = false): String = """
            CONF=$CONFIG_PATH
            BB=/data/adb/magisk/busybox
            [ -x "${'$'}BB" ] || BB=busybox
            IE=$ELEMENT
            FORCE=$forceRestart
            [ -f "${'$'}CONF" ] || { echo no-config; exit 0; }
            if [ "${'$'}FORCE" != "true" ] && grep -q "^vendor_elements=.*${'$'}IE" "${'$'}CONF" 2>/dev/null; then
              echo present
              exit 0
            fi
            if ! grep -q "^vendor_elements=.*${'$'}IE" "${'$'}CONF" 2>/dev/null; then
              ${'$'}BB chattr -i "${'$'}CONF" >/dev/null 2>&1
              printf "\nvendor_elements=${'$'}IE\n" >> "${'$'}CONF"
            fi
            chmod 444 "${'$'}CONF" >/dev/null 2>&1
            ${'$'}BB chattr +i "${'$'}CONF" >/dev/null 2>&1
            # hostapd only publishes vendor_elements when it creates the interface.
            svc wifi disable
            sleep 2
            svc wifi enable
            echo restarted
            sleep 4
        """.trimIndent()

        private fun defaultRootExecute(script: String): String? = runCatching {
            val process = ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val reader = Thread({
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line -> if (output.length < 4_000) output.append(line).append('\n') }
                    }
                }
            }, "xcertplay-vendor-element").apply { isDaemon = true; start() }
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) runCatching { process.destroy() }
            reader.join(500)
            if (finished) output.toString() else null
        }.getOrNull()
    }
}
