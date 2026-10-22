package com.shilapi.xcertplay.web

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Exercises the real UDP receive loop; the Root DNAT path itself still needs a device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TeslaWifiProbeServerTest {
    private fun query(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        for (label in name.split('.')) {
            out.write(label.length); out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(byteArrayOf(0, 0, 1, 0, 1))
        return out.toByteArray()
    }

    @Test fun answersProbeQueriesOverUdpAndCanRestart() {
        val server = TeslaWifiProbeServer()
        try {
            assertTrue(server.start("127.0.0.1", "100.96.0.1") { emptyList() })
            probe("connman.vn.cloud.tesla.cn")
            assertEquals(1, server.requests)
            assertTrue(server.running)
            server.stop()
            assertFalse(server.running)
            assertTrue(server.start("127.0.0.1", "100.96.0.1") { emptyList() })
            probe("connman.vn.cloud.tesla.com")
            assertEquals(2, server.requests)
        } finally {
            server.stop()
        }
    }

    private fun probe(name: String) {
        DatagramSocket().use { client ->
            client.soTimeout = 3000
            val request = query(name)
            client.send(DatagramPacket(request, request.size, InetAddress.getByName("127.0.0.1"), TeslaWifiProbe.DNS_PORT))
            val buffer = ByteArray(512)
            val response = DatagramPacket(buffer, buffer.size)
            client.receive(response)
            assertEquals(0x1234, ProbeDns.u16(buffer, 0))
            assertEquals(1, ProbeDns.u16(buffer, 6))
        }
    }
}
