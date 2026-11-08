package com.shilapi.xcertplay.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.Inet4Address
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProbeUpstreamPreferenceTest {
    private val local = InetAddress.getByName("100.96.0.1") as Inet4Address
    private val upstream = byteArrayOf(1, 2, 3, 4, 5)
    private fun query(name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        for (label in name.split('.')) {
            out.write(label.length); out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(byteArrayOf(0, 0, 1, 0, 1))
        return out.toByteArray()
    }

    @Test
    fun withAnUplinkTheProbeNameIsResolvedUpstream() {
        // The car should reach the real service when the hotspot actually has internet.
        val reply = ProbeDns.answer(query("www.baidu.cn"), local, forward = { upstream }, preferUpstream = true)

        assertArrayEquals(upstream, reply)
    }

    @Test
    fun withoutAnUplinkTheProbeNameStillGetsTheLocalAlias() {
        // Nothing to forward to: fall back to this device so the car stays "online".
        val reply = ProbeDns.answer(query("www.baidu.cn"), local, forward = { null }, preferUpstream = true)!!

        assertEquals(1, ProbeDns.u16(reply, 6))
        assertArrayEquals(local.address, reply.copyOfRange(reply.size - 4, reply.size))
    }

    @Test
    fun withoutThePreferenceTheLocalAliasWins() {
        var forwarded = false
        val reply = ProbeDns.answer(query("connman.vn.cloud.tesla.cn"), local, forward = {
            forwarded = true
            upstream
        }, preferUpstream = false)!!

        assertEquals(false, forwarded)
        assertArrayEquals(local.address, reply.copyOfRange(reply.size - 4, reply.size))
    }

    @Test
    fun ordinaryNamesAreAlwaysForwarded() {
        val reply = ProbeDns.answer(query("example.com"), local, forward = { upstream })!!

        assertArrayEquals(upstream, reply)
    }
}
