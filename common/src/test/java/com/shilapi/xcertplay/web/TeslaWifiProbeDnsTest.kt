package com.shilapi.xcertplay.web

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress

class TeslaWifiProbeDnsTest {
    private val accessAddress = InetAddress.getByName("100.96.0.1") as Inet4Address

    private fun query(name: String, type: Int = 1, id: Int = 0x1234): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id shr 8); out.write(id and 0xFF)
        out.write(0x01); out.write(0x00) // RD set, standard query
        out.write(byteArrayOf(0, 1, 0, 0, 0, 0, 0, 0))
        for (label in name.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        out.write(type shr 8); out.write(type and 0xFF)
        out.write(0); out.write(1)
        return out.toByteArray()
    }

    private fun u16(data: ByteArray, offset: Int) =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun answerOffset(name: String) = 12 + name.split('.').sumOf { it.length + 1 } + 1 + 4

    @Test fun probeNameIsAnsweredWithTheTeslaAccessAddress() {
        val name = "connman.vn.cloud.tesla.cn"
        val response = ProbeDns.answer(query(name), accessAddress) { fail("probe names must not be forwarded"); null }!!
        assertEquals(0x1234, u16(response, 0))
        assertEquals(0x80, response[2].toInt() and 0x80) // QR
        assertEquals(0x01, response[2].toInt() and 0x01) // RD preserved
        assertEquals(0x80, response[3].toInt() and 0x80) // RA
        assertEquals(0, response[3].toInt() and 0x0F) // NOERROR
        assertEquals(1, u16(response, 4))
        assertEquals(1, u16(response, 6))
        val answer = answerOffset(name)
        assertEquals(0xC0, response[answer].toInt() and 0xFF)
        assertEquals(0x0C, response[answer + 1].toInt() and 0xFF)
        assertEquals(1, u16(response, answer + 2)) // A
        assertEquals(1, u16(response, answer + 4)) // IN
        assertEquals(60, u16(response, answer + 8)) // TTL
        assertEquals(4, u16(response, answer + 10))
        assertArrayEquals(byteArrayOf(100, 96, 0, 1), response.copyOfRange(answer + 12, answer + 16))
    }

    @Test fun namesAreMatchedWithoutCaseAndWithATrailingDot() {
        for (name in listOf("CONNMAN.vn.Cloud.Tesla.CN", "connman.vn.cloud.tesla.com", "connman.vn.tesla.services")) {
            assertTrue(name, ProbeDns.isProbe(query(name)))
            assertEquals(1, u16(ProbeDns.answer(query(name), accessAddress) { null }!!, 6))
        }
    }

    @Test fun aaaaAndOtherTypesReturnNoDataSoTheClientFallsBackToA() {
        for (type in listOf(28, 65, 16)) {
            val response = ProbeDns.answer(query("connman.vn.cloud.tesla.cn", type), accessAddress) { null }!!
            assertEquals(0, response[3].toInt() and 0x0F)
            assertEquals(0, u16(response, 6))
        }
    }

    @Test fun unrelatedNamesAreForwardedUnchanged() {
        val forwarded = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(forwarded, ProbeDns.answer(query("example.com"), accessAddress) { forwarded })
    }

    @Test fun aFailedForwardBecomesServfailInsteadOfLeakingAnAnswer() {
        val response = ProbeDns.answer(query("example.com"), accessAddress) { null }!!
        assertEquals(2, response[3].toInt() and 0x0F)
        assertEquals(0, u16(response, 6))
    }

    @Test fun malformedOrResponsesAreIgnored() {
        assertNull(ProbeDns.answer(byteArrayOf(1, 2, 3), accessAddress) { null })
        val response = query("connman.vn.cloud.tesla.cn").also { it[2] = 0x81.toByte() }
        assertNull(ProbeDns.answer(response, accessAddress) { null })
        assertFalse(ProbeDns.isProbe(byteArrayOf(0, 0)))
    }

    @Test fun statusPageMatchesTheProductionResponse() {
        assertEquals("/online/status.html", TeslaWifiProbe.STATUS_PATH)
        assertEquals(46, TeslaWifiProbe.STATUS_BODY.size)
        assertEquals("online", TeslaWifiProbe.STATUS_HEADER_VALUE)
        assertEquals("<html>\n<head>\n</head>\n<body>\n</body>\n</html>\n\n",
            String(TeslaWifiProbe.STATUS_BODY, Charsets.US_ASCII))
    }
}
