package com.shilapi.xcertplay.diagnostics

/**
 * Facts about the last wireless join attempt, phrased for the exported diagnostic report.
 *
 * The exported report deliberately drops any log line that mentions credentials or the network
 * name, which is exactly the material this investigation needs. So the join-relevant facts are
 * collected here under neutral keys — no network name, no passphrase, no addresses — together with
 * a one-line result and the next thing to check.
 *
 * Process-local and reset at the start of every wireless bring-up; the newest attempt wins.
 */
object WirelessHandoffRecord {
    private val lock = Any()
    private val facts = LinkedHashMap<String, String>()
    private var result: String? = null
    private var next: String? = null

    fun reset() = synchronized(lock) {
        facts.clear()
        result = null
        next = null
    }

    fun put(key: String, value: Any?) = synchronized(lock) {
        if (value == null) {
            facts.remove(key)
        } else {
            facts[key] = value.toString()
        }
    }

    /** Records the outcome; [next] is the concrete next check for the user. */
    fun result(result: String, next: String? = null) = synchronized(lock) {
        this.result = result
        if (next != null) this.next = next
    }

    /** True once anything has been recorded for the current attempt. */
    fun isEmpty(): Boolean = synchronized(lock) { facts.isEmpty() && result == null }

    fun report(): String = synchronized(lock) {
        if (facts.isEmpty() && result == null) {
            return@synchronized "no wireless join attempt recorded"
        }
        buildString {
            appendLine("result=" + (result ?: "in-progress"))
            facts.forEach { (key, value) -> appendLine("$key=$value") }
            appendLine("next=" + (next ?: suggestNext(facts, result)))
        }.trimEnd()
    }

    /** One redaction-safe log line, so the same facts also survive in the raw log. */
    fun summaryLine(): String = synchronized(lock) {
        if (facts.isEmpty() && result == null) return@synchronized "wireless join: no attempt yet"
        "wireless join: result=" + (result ?: "in-progress") +
            (if (facts.isEmpty()) "" else " " + facts.entries.joinToString(" ") { "${it.key}=${it.value}" }) +
            " next=" + (next ?: suggestNext(facts, result))
    }
}

/**
 * The concrete next check for a failed join, derived only from the recorded facts. Kept here so the
 * report and the summary line cannot disagree, and so the mapping is unit-testable.
 */
internal fun suggestNext(facts: Map<String, String>, result: String? = null): String = when {
    result == "joined" -> "nothing to check: the phone joined this hotspot"
    facts["uplink"] == "iphone-hotspot" && facts["uplink-held"] == "yes" ->
        "the app already held this device's uplink down for the whole window and the iPhone " +
            "still did not join; turn its Personal Hotspot off manually and retry"
    facts["uplink"] == "iphone-hotspot" ->
        "this device is a client of the iPhone's Personal Hotspot (172.20.10.x), and an iPhone " +
            "serving that hotspot does not join the CarPlay hotspot; turn the Personal Hotspot " +
            "off or give the head unit another uplink"
    facts["iap2-wireless-available"] == "false" ->
        "the iPhone did not offer wireless CarPlay for this accessory; check iPhone Settings > " +
            "General > CarPlay and the Enable Wireless CarPlay prompt"
    facts["iap2-credentials-requests"] == null || facts["iap2-credentials-requests"] == "0" ->
        "the session never asked for the hotspot credentials (0x5702); the join step was never " +
            "reached, so credentials are not the cause"
    facts["iap2-config-sent"] == "0" ->
        "the accessory sent no Wi-Fi configuration at all"
    facts["hotspot-ap-mode"] == "false" ->
        "the hotspot interface is not in AP mode, so nothing is being broadcast; turn the hotspot on"
    facts["hotspot-hidden"] == "true" ->
        "the hotspot hides its name and iOS never joins hidden networks; unhide it"
    facts["hotspot-name-check"] == "differs" ->
        "the broadcast hotspot name differs from the saved one; see the hotspot notice"
    facts["hotspot-security"] == "WPA3_ONLY" ->
        "the hotspot is WPA3-only; use WPA2 or WPA3 transition"
    // The phone asked for the configuration and got it, yet never associated: the remaining
    // causes are on the phone side (a stale record for this network) or a value we could only
    // guess because Android hides the live access point.
    facts["key-source"] == "saved settings" ->
        "the phone received the configuration and still did not associate; on the iPhone forget " +
            "this network and retry, and make sure the saved key matches the phone because " +
            "Android did not expose the live one"
    else ->
        "the phone received the configuration and still did not associate; on the iPhone forget " +
            "this network and retry"
}
