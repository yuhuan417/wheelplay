package com.shilapi.xcertplay.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.net.URL

@RunWith(AndroidJUnit4::class)
class WebRuntimeTest {
    @Test fun actualHttpWebSocketAndRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WebSession.start(context)
        try {
            assertTrue(WebSession.error, WebSession.running)
            assertTrue(URL("http://127.0.0.1:8080/").readText().contains("打开即连接"))
            assertTrue(URL("http://127.0.0.1:8080/app.js").readText().contains("pointercancel"))
            handshake("http://evil.invalid").use { assertTrue(readHeaders(it).startsWith("HTTP/1.1 403")) }
            handshake().use { client ->
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                assertEquals("audio-route",
                    org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getString("type"))
            }
        } finally { WebSession.stop() }
        assertFalse(WebSession.running)
        WebSession.start(context)
        try { assertTrue("Port must be reusable after stop", WebSession.running) }
        finally { WebSession.stop() }
    }

    private fun handshake(origin: String = "http://127.0.0.1:8080"): Socket = Socket("127.0.0.1", 8080).apply {
        soTimeout = 3000
        getOutputStream().write(("GET /stream HTTP/1.1\r\nHost: 127.0.0.1:8080\r\n" +
            "Origin: $origin\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n").toByteArray())
        getOutputStream().flush()
    }
    private fun readHeaders(socket: Socket): String {
        val result = StringBuilder()
        while (!result.endsWith("\r\n\r\n")) {
            val next = socket.getInputStream().read(); check(next >= 0); result.append(next.toChar())
        }
        return result.toString()
    }
    private fun frame(socket: Socket): ByteArray {
        val input = socket.getInputStream()
        check(input.read() >= 0)
        val length = input.read() and 127
        check(length < 126)
        val result = ByteArray(length)
        for (index in result.indices) result[index] = input.read().also { check(it >= 0) }.toByte()
        return result
    }
}
