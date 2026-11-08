package com.shilapi.xcertplay.web

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TeslaWifiProbeAnswerTest {
    @Test
    fun everyCheckNameIsAnswered() {
        val names = listOf(
            "connman.vn.cloud.tesla.cn", "connman.vn.tesla.services", "connman.vn.cloud.tesla.com",
            "www.baidu.cn", "www.baidu.com", "www.qq.com", "www.tesla.cn",
            "connectivitycheck.gstatic.com",
        )

        names.forEach { name ->
            assertEquals(name, name, TeslaWifiProbe.normalizeHost(name))
            assertTrue("$name is not answered", TeslaWifiProbe.answer(name, "/") != null)
        }
    }

    @Test
    fun aChineseCarAsksForBaiduQqAndTeslaToo() {
        // ConnMan alone was proven insufficient on a China car: these are checked as well.
        assertTrue(TeslaWifiProbe.HOSTS.containsAll(
            listOf("www.baidu.cn", "www.qq.com", "www.tesla.cn", "www.baidu.com"),
        ))
    }

    @Test
    fun connmanNamesGetTheStatusPageAndHeaders() {
        for (name in TeslaWifiProbe.CONNMAN_HOSTS) {
            val answer = TeslaWifiProbe.answer(name, "/online/status.html")!!
            assertEquals(200, answer.status)
            assertTrue(answer.connman)
            assertArrayEquals(TeslaWifiProbe.STATUS_BODY, answer.body)
        }
    }

    @Test
    fun theExtraNamesGetAShortOnlinePage() {
        val answer = TeslaWifiProbe.answer("www.baidu.cn", "/")!!
        assertEquals(200, answer.status)
        val text = String(answer.body, Charsets.UTF_8)
        assertTrue(text.contains("online"))
        // Only ConnMan responses carry the status header.
        assertEquals(false, answer.connman)
    }

    @Test
    fun chromiumExpectsAnEmpty204() {
        val generate = TeslaWifiProbe.answer("connectivitycheck.gstatic.com", "/generate_204")!!
        assertEquals(204, generate.status)
        assertEquals(0, generate.body.size)

        assertEquals(204, TeslaWifiProbe.answer("connectivitycheck.gstatic.com", "/generate_204?x=1")!!.status)
        assertEquals(204, TeslaWifiProbe.answer("connectivitycheck.gstatic.com", "/gen_204")!!.status)

        // Other paths on the same host still get a normal page.
        assertEquals(200, TeslaWifiProbe.answer("connectivitycheck.gstatic.com", "/")!!.status)
    }

    @Test
    fun theHostIsMatchedLogically() {
        assertEquals("www.baidu.com", TeslaWifiProbe.normalizeHost("WWW.BAIDU.COM.:80"))
        assertEquals("connman.vn.cloud.tesla.cn", TeslaWifiProbe.normalizeHost("CONNMAN.vn.Cloud.Tesla.CN:80"))
        assertEquals("www.tesla.cn", TeslaWifiProbe.normalizeHost("www.tesla.cn."))
        assertNull(TeslaWifiProbe.normalizeHost(""))
        assertNull(TeslaWifiProbe.normalizeHost(null))
    }

    @Test
    fun ordinaryHostsAreNotAnswered() {
        assertNull(TeslaWifiProbe.answer("example.com", "/"))
        assertNull(TeslaWifiProbe.answer("httpbin.org", "/generate_204"))
    }

    @Test
    fun theStatusPathStillWorksWhenTheHostIsAnAddress() {
        // Reaching the box by IP has no probe host, so the documented path is kept as a fallback.
        assertTrue(TeslaWifiProbe.answer("100.96.0.1", "/online/status.html") != null)
        assertNull(TeslaWifiProbe.answer("100.96.0.1", "/"))
    }
}
