package com.shilapi.xcertplay.network

import java.util.concurrent.TimeUnit

/**
 * Reads the live access-point channel and security type from the rooted system when the public
 * `SoftApConfiguration` API is unavailable (MIUI hides it, as the field report shows).
 *
 * Only observed values are used: an unreadable probe leaves the guess in place and says so. The
 * probe never parses or returns the passphrase, and the dump is filtered on the device so no
 * configuration file is read into the app.
 */
class HotspotRootProbe(
    private val execute: (String) -> String? = ::defaultRootExecute,
) {
    fun probe(interfaceName: String?): RootHotspotObservation? {
        if (interfaceName.isNullOrBlank()) return null
        val script = buildString {
            appendLine("echo __IAP2_IW__")
            append("iw dev ").append(interfaceName).append(" info 2>&1")
            appendLine()
            appendLine("echo __IAP2_AP__")
            appendLine(
                "dumpsys wifi 2>/dev/null | grep -i -E \"softap|apchannel|securityType\" | head -n 40",
            )
        }
        val output = runCatching { execute(script) }.getOrNull()
        if (output.isNullOrBlank()) return null
        return parseRootHotspotObservation(output)
    }

    private companion object {
        fun defaultRootExecute(script: String): String? {
            val process = ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val reader = Thread({
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line -> if (output.length < 8_000) output.append(line).append('\n') }
                    }
                }
            }, "hotspot-root-probe").apply { isDaemon = true; start() }
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                runCatching { process.destroy() }
                return null
            }
            reader.join(500)
            return output.toString()
        }
    }
}

/** What a rooted probe could observe; every field is null when the ROM did not report it. */
data class RootHotspotObservation(
    val channel: Int?,
    val frequencyMHz: Int?,
    val apMode: Boolean?,
    val securityType: Int?,
)

private val IW_CHANNEL = Regex("""channel\s+(\d+)\s*\((\d+)\s*MHz""", RegexOption.IGNORE_CASE)
private val IW_TYPE = Regex("""type\s+(\w+)""", RegexOption.IGNORE_CASE)
private val DUMPSYS_SECURITY = Regex("""securityType\s*[=:]\s*(\d+)""", RegexOption.IGNORE_CASE)
private val DUMPSYS_CHANNEL = Regex("""\bapChannel\s*[=:]\s*(-?\d+)""", RegexOption.IGNORE_CASE)

internal fun parseRootHotspotObservation(output: String): RootHotspotObservation? {
    val iw = output.substringAfter("__IAP2_IW__", "").substringBefore("__IAP2_AP__", output)
    val dump = output.substringAfter("__IAP2_AP__", "")
    val channelMatch = IW_CHANNEL.find(iw)
    val frequency = channelMatch?.groupValues?.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 }
    val channel = frequency?.let(::wifiFrequencyMhzToChannel)
        ?: channelMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
    val apMode = IW_TYPE.find(iw)?.groupValues?.getOrNull(1)?.let {
        it.equals("AP", ignoreCase = true)
    }
    val security = DUMPSYS_SECURITY.find(dump)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?.takeIf { it in 0..4 }
    val dumpChannel = DUMPSYS_CHANNEL.find(dump)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?.takeIf { it > 0 }
    if (channel == null && apMode == null && security == null && dumpChannel == null) return null
    return RootHotspotObservation(
        channel = channel ?: dumpChannel,
        frequencyMHz = frequency,
        apMode = apMode,
        securityType = security,
    )
}
