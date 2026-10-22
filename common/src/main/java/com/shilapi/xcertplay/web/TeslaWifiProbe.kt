package com.shilapi.xcertplay.web

import android.util.Log
import com.shilapi.xcertplay.DiagnosticLogSink
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Answers the car's ConnMan Wi-Fi internet check locally.
 *
 * Tesla's connection manager probes `http://connman.vn.cloud.tesla.cn/online/status.html` over
 * plain HTTP and treats a 200 response as "online". Because the probe is not TLS, a rooted phone
 * can answer it without impersonating Tesla's certificate: [ProbeDns] resolves the probe host names
 * to the Tesla compatibility address, and [LanWebServer] serves [STATUS_BODY] on that address
 * through the existing low-port mapping.
 */
internal object TeslaWifiProbe {
    /** Unprivileged DNS port; root DNATs the hotspot's UDP 53 here. */
    const val DNS_PORT = 15353
    const val STATUS_PATH = "/online/status.html"

    /** Probe names used by Tesla's ConnMan builds; all are answered with the access address. */
    val HOSTS = setOf(
        "connman.vn.cloud.tesla.cn",
        "connman.vn.cloud.tesla.com",
        "connman.vn.tesla.services",
    )

    /** Exact 46-byte body served by the production endpoint, including the trailing blank line. */
    val STATUS_BODY = "<html>\n<head>\n</head>\n<body>\n</body>\n</html>\n\n".toByteArray(Charsets.US_ASCII)
    const val STATUS_HEADER = "X-ConnMan-Status"
    const val STATUS_HEADER_VALUE = "online"
}

/** Minimal DNS wire handling: answer the probe names and relay everything else to the uplink DNS. */
internal object ProbeDns {
    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28
    private const val CLASS_IN = 1
    private const val RCODE_SERVFAIL = 2

    internal class Question(val name: String, val type: Int, val qclass: Int)

    /** Parses a one-question standard query; compressed question names are accepted. */
    fun parse(packet: ByteArray): Question? {
        if (packet.size < 12) return null
        // QR must be clear and the opcode must be a standard query.
        if ((packet[2].toInt() and 0x80) != 0 || (packet[2].toInt() and 0x78) != 0) return null
        if (u16(packet, 4) != 1) return null
        val (name, end) = readName(packet, 12) ?: return null
        if (end + 4 > packet.size) return null
        return Question(name, u16(packet, end), u16(packet, end + 2))
    }

    fun isProbe(packet: ByteArray): Boolean {
        val question = parse(packet) ?: return false
        return question.qclass == CLASS_IN && question.name.trimEnd('.').lowercase() in TeslaWifiProbe.HOSTS
    }

    /**
     * Returns a DNS response for [packet], or null when the packet is not a usable query.
     * Non-probe names are delegated to [forward]; a failed forward becomes SERVFAIL.
     */
    fun answer(packet: ByteArray, address: Inet4Address, forward: (ByteArray) -> ByteArray?): ByteArray? {
        val question = parse(packet) ?: return null
        if (question.qclass != CLASS_IN || question.name.trimEnd('.').lowercase() !in TeslaWifiProbe.HOSTS) {
            return forward(packet) ?: response(packet, question, null, RCODE_SERVFAIL)
        }
        return when (question.type) {
            TYPE_A -> response(packet, question, address.address, 0)
            // AAAA and other types return NOERROR with no answer so the client falls back to A.
            TYPE_AAAA -> response(packet, question, null, 0)
            else -> response(packet, question, null, 0)
        }
    }

    private fun response(packet: ByteArray, question: Question, ip: ByteArray?, rcode: Int): ByteArray {
        val questionBytes = encodeQuestion(question)
        val answerBytes = ip?.let(::encodeAnswer)
        val out = ByteArray(12 + questionBytes.size + (answerBytes?.size ?: 0))
        out[0] = packet[0]
        out[1] = packet[1]
        // QR | RD (copied) and RA | RCODE. AA is clear because this is a synthesized answer.
        out[2] = (0x80 or (packet[2].toInt() and 0x01)).toByte()
        out[3] = (0x80 or rcode).toByte()
        out[5] = 1
        out[7] = if (answerBytes != null) 1 else 0
        questionBytes.copyInto(out, 12)
        answerBytes?.copyInto(out, 12 + questionBytes.size)
        return out
    }

    private fun encodeQuestion(question: Question): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encodeName(question.name))
        out.write(question.type shr 8)
        out.write(question.type and 0xFF)
        out.write(question.qclass shr 8)
        out.write(question.qclass and 0xFF)
        return out.toByteArray()
    }

    private fun encodeAnswer(ip: ByteArray): ByteArray = ByteArray(16).apply {
        // Compressed name pointer to the question at offset 12.
        this[0] = 0xC0.toByte()
        this[1] = 0x0C
        this[3] = TYPE_A.toByte()
        this[5] = CLASS_IN.toByte()
        // 60-second TTL.
        this[9] = 60
        this[11] = 4
        ip.copyInto(this, 12)
    }

    private fun encodeName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (label in name.split('.')) {
            if (label.isEmpty()) continue
            val bytes = label.toByteArray(Charsets.ISO_8859_1)
            require(bytes.size <= 63) { "DNS label too long" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun readName(packet: ByteArray, start: Int): Pair<String, Int>? {
        val labels = mutableListOf<String>()
        var position = start
        var end = -1
        var hops = 0
        while (true) {
            if (position >= packet.size) return null
            val length = packet[position].toInt() and 0xFF
            if (length == 0) {
                position++
                break
            }
            if (length and 0xC0 == 0xC0) {
                if (position + 1 >= packet.size) return null
                val pointer = ((length and 0x3F) shl 8) or (packet[position + 1].toInt() and 0xFF)
                if (end < 0) end = position + 2
                if (++hops > 16 || pointer >= packet.size) return null
                position = pointer
                continue
            }
            if (length > 63 || position + 1 + length > packet.size) return null
            labels += String(packet, position + 1, length, Charsets.ISO_8859_1)
            position += 1 + length
        }
        return labels.joinToString(".") to (if (end >= 0) end else position)
    }

    internal fun u16(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
}

/**
 * UDP DNS endpoint for the hotspot's redirected queries. It binds [start]'s listen host (wildcard in
 * production) and answers probe names with the Tesla compatibility address. [start] is idempotent;
 * the Root routing owner starts and stops it, and the socket dies with the process.
 */
internal class TeslaWifiProbeServer {
    private val executor: ExecutorService = Executors.newFixedThreadPool(8) { work ->
        Thread(work, "tesla-wifi-probe-dns").apply { isDaemon = true }
    }
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private var listenAddress: Inet4Address? = null
    private var answerAddress: Inet4Address? = null
    private var upstreams: () -> List<InetAddress> = { emptyList() }
    private val counted = AtomicInteger()

    @Volatile var running = false
        private set
    @Volatile var error: String? = null
        private set
    val requests get() = counted.get()

    @Synchronized
    fun start(listenHost: String, answerHost: String, upstream: () -> List<InetAddress>): Boolean {
        val listen = parseAddress(listenHost) ?: return fail("DNS 监听地址无效")
        val answer = parseAddress(answerHost) ?: return fail("访问 IP 无效")
        // Keep the existing upstream closure (and its cache) while the endpoint is unchanged.
        if (running && listenAddress == listen && answerAddress == answer &&
            thread?.isAlive == true && socket?.isClosed == false) return true
        closeSocket()
        return try {
            val bound = DatagramSocket(null).apply {
                reuseAddress = false
                bind(InetSocketAddress(listen, TeslaWifiProbe.DNS_PORT))
            }
            socket = bound
            listenAddress = listen
            answerAddress = answer
            upstreams = upstream
            error = null
            running = true
            thread = Thread({ receive(bound, answer) }, "tesla-wifi-probe").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "Wi-Fi probe DNS listening on ${listen.hostAddress}:${TeslaWifiProbe.DNS_PORT} for ${answer.hostAddress}")
            true
        } catch (failure: Exception) {
            fail("DNS 应答监听失败：${failure.message ?: failure.javaClass.simpleName}")
        }
    }

    @Synchronized
    fun stop() {
        running = false
        closeSocket()
        thread?.let { runCatching { it.join(500) } }
        thread = null
        listenAddress = null
        answerAddress = null
    }

    private fun parseAddress(value: String): Inet4Address? =
        runCatching { InetAddress.getByName(value) as Inet4Address }.getOrNull()

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun fail(message: String): Boolean {
        error = message
        running = false
        closeSocket()
        return false
    }

    private fun receive(bound: DatagramSocket, local: Inet4Address) {
        val buffer = ByteArray(1500)
        while (running && !bound.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                bound.receive(packet)
            } catch (_: SocketException) {
                break
            } catch (_: IOException) {
                continue
            }
            val query = buffer.copyOf(packet.length)
            val client = packet.address
            val port = packet.port
            if (ProbeDns.isProbe(query)) {
                counted.incrementAndGet()
                Log.i(TAG, "Wi-Fi probe DNS query from ${client.hostAddress}")
                DiagnosticLogSink.append("Wi-Fi 联网检测：${client.hostAddress} 查询 probe 域名")
                // Answer the probe inline so it never queues behind uplink lookups.
                val reply = runCatching { ProbeDns.answer(query, local) { null } }.getOrNull()
                send(bound, reply, client, port)
            } else {
                executor.execute {
                    val reply = runCatching { ProbeDns.answer(query, local, ::forward) }.getOrNull()
                    send(bound, reply, client, port)
                }
            }
        }
    }

    private fun send(bound: DatagramSocket, reply: ByteArray?, target: InetAddress, port: Int) {
        if (reply == null) return
        runCatching { bound.send(DatagramPacket(reply, reply.size, target, port)) }
    }

    /** Relays a query to the first upstream that answers within the shared deadline. */
    private fun forward(query: ByteArray): ByteArray? {
        val servers = upstreams().distinct()
        if (servers.isEmpty()) return null
        val deadline = System.nanoTime() + 2_000_000_000L
        for ((index, server) in servers.withIndex()) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
            if (remaining <= 0) break
            // Give every upstream a chance instead of letting a blackholed gateway eat the budget.
            val timeout = (remaining / (servers.size - index)).coerceAtLeast(300)
            try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = timeout
                    socket.send(DatagramPacket(query, query.size, server, 53))
                    val buffer = ByteArray(4096)
                    val response = DatagramPacket(buffer, buffer.size)
                    socket.receive(response)
                    if (response.length >= 12 && buffer[0] == query[0] && buffer[1] == query[1]) {
                        return buffer.copyOf(response.length)
                    }
                }
            } catch (_: IOException) {
                // Try the next upstream.
            }
        }
        return null
    }

    private companion object {
        const val TAG = "WheelPlayTesla"
    }
}
