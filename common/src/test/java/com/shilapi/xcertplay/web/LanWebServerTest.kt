package com.shilapi.xcertplay.web

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.HttpsURLConnection

@RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [29])
class LanWebServerTest {
    @org.junit.Before fun provisionAuthentication() {
        val identity = com.shilapi.xcertplay.SyntheticMfiIdentity.create()
        com.shilapi.xcertplay.MfiAssetStore(org.robolectric.RuntimeEnvironment.getApplication().noBackupFilesDir)
            .installFiles({ identity.key.inputStream() }, { identity.certificate.inputStream() })
    }

    @Test fun changingHttpPortPreservesMediaAndUsesSavedPortOnRestart() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        WebSession.start(context)
        try {
            val port = java.net.ServerSocket(0).use { it.localPort }
            assertNull(WebSession.setHttpPort(context, port))
            assertEquals(port, WebSession.httpPort)
            assertEquals(port, WebListenSettings.httpPort(context))
            assertFalse(TeslaHttpCompatibility.config(context).enabled)
            assertTrue(URL("http://127.0.0.1:$port/").readText().contains("打开即连接"))
            for (host in listOf("100.96.0.1:$port", "car.example.com:$port")) {
                handshake("", "http://$host", "", host, port).use {
                    assertTrue(readHeaders(it).startsWith("HTTP/1.1 101"))
                }
                handshake("", "http://${host.substringBefore(':')}:8080", "", host, port).use {
                    assertTrue(readHeaders(it).startsWith("HTTP/1.1 403"))
                }
            }
            WebSession.stop(); WebSession.start(context)
            assertEquals(port, WebSession.httpPort)
            assertTrue(URL("http://127.0.0.1:$port/app.js").readText().contains("pointercancel"))
            handshake("", "http://127.0.0.1:$port", "", "127.0.0.1:$port", port).use {
                assertTrue(readHeaders(it).startsWith("HTTP/1.1 101"))
            }
        } finally { WebSession.stop() }
    }

    @Test fun occupiedPortKeepsTheOriginalListenerConfigUsable() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        WebSession.start(context)
        try {
            val oldConfig = TeslaHttpCompatibility.config(context)
            java.net.ServerSocket(0).use { occupied ->
                val failure = WebSession.setHttpPort(context, occupied.localPort)
                assertNotNull(failure)
                assertTrue(failure!!.contains("原端口 8080 继续运行"))
            }
            assertTrue(WebSession.running)
            assertNull(WebSession.error)
            assertEquals(8080, WebSession.httpPort)
            assertEquals(oldConfig, TeslaHttpCompatibility.config(context))
            assertTrue(URL("http://127.0.0.1:8080/").readText().contains("打开即连接"))
            handshake().use { assertTrue(readHeaders(it).startsWith("HTTP/1.1 101")) }
        } finally { WebSession.stop() }
    }

    @Test fun httpVirtualIpAndDomainKeepSameOriginChecks() {
        WebSession.start(org.robolectric.RuntimeEnvironment.getApplication())
        try {
            for (host in listOf("100.96.0.1:8080", "car.example.com:8080")) {
                handshake("", "http://evil.invalid", "", host).use {
                    assertTrue(readHeaders(it).startsWith("HTTP/1.1 403"))
                }
                handshake("", "http://$host", "", host).use {
                    assertTrue(readHeaders(it).startsWith("HTTP/1.1 101"))
                    assertEquals("audio-route", org.json.JSONObject(frame(it).toString(Charsets.UTF_8)).getString("type"))
                }
            }
        } finally { WebSession.stop() }
    }

    @Test fun trustedHttpsViewerCanSupplyNegotiatedMicrophonePcm() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        WebSession.start(context)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (WebSession.tls == null && WebSession.tlsError == null && System.nanoTime() < deadline) Thread.sleep(20)
            val endpoint = WebSession.tls ?: error("HTTPS failed: ${WebSession.tlsError}")
            val root = CertificateFactory.getInstance("X.509")
                .generateCertificate(endpoint.certificate.inputStream())
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("wheelplay", root) }
            val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
            (ssl.socketFactory.createSocket("127.0.0.1", 8443) as SSLSocket).use { client ->
                client.soTimeout = 3000
                client.sslParameters = client.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                client.startHandshake()
                client.outputStream.write(("GET /stream HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:8443\r\nOrigin: https://127.0.0.1:8443\r\n" +
                    "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n").toByteArray())
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                assertEquals("audio-route", org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getString("type"))
                WebSession.audio.configure(false, true)
                assertTrue(org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getBoolean("microphone"))
                text(client, """{"type":"audio-ready","playback":false,"microphone":true}""")
                val route = WebSession.audio.createRoute()
                try {
                    val config = com.shilapi.xcertplay.airplay.MicrophoneConfig("telephony", 16_000, 1, 97, 20,
                        java.net.InetAddress.getLoopbackAddress(), 12345, ByteArray(32))
                    var input: com.shilapi.xcertplay.media.PcmInput? = null
                    val ready = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (input == null && System.nanoTime() < ready) {
                        input = route.microphone(1, config)
                        if (input == null) Thread.sleep(10)
                    }
                    assertNotNull(input)
                    val mic = org.json.JSONObject(frame(client).toString(Charsets.UTF_8))
                    assertEquals("mic-config", mic.getString("type"))
                    val wire = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putInt(BrowserAudioBridge.MIC_MAGIC).putInt(mic.getInt("id")).putInt(0)
                        .put(byteArrayOf(1,2,3,4)).array()
                    maskedFrame(client, 2, wire)
                    val pcm = ByteArray(8)
                    var count = 0
                    val receivedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (count == 0 && System.nanoTime() < receivedBy) count = input!!.read(pcm)
                    assertEquals(4, count)
                    assertArrayEquals(byteArrayOf(1,2,3,4), pcm.copyOf(4))
                } finally { route.close() }
            }
        } finally { WebSession.stop() }
    }
    @Test fun viewerReceivesDecodedPcmOnTheExistingSocket() {
        WebSession.start(org.robolectric.RuntimeEnvironment.getApplication())
        try {
            handshake().use { client ->
                client.soTimeout = 3000
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                assertEquals("audio-route", org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getString("type"))
                WebSession.audio.configure(true, false)
                assertTrue(org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getBoolean("playback"))
                text(client, """{"type":"audio-ready","playback":true,"microphone":false}""")
                val route = WebSession.audio.createRoute()
                try {
                    val format = com.shilapi.xcertplay.airplay.AudioFormat(
                        com.shilapi.xcertplay.airplay.AudioCodecKind.LPCM, 48_000, 1, 96, "media")
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (!route.output(3, format, byteArrayOf(1,2,3,4), 0, 4) && System.nanoTime() < deadline) Thread.sleep(10)
                    val received = java.nio.ByteBuffer.wrap(frame(client)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    assertEquals(BrowserAudioBridge.AUDIO_MAGIC, received.int)
                    assertEquals(3, received.int)
                    assertEquals(48_000, received.int)
                    assertEquals(1, received.short.toInt())
                    assertEquals(BrowserAudioBridge.AUDIO_FLAG_MEDIA, received.short.toInt())
                    val sequence = received.int.toLong() and 0xffff_ffffL
                    val samples = ByteArray(4); received.get(samples)
                    assertArrayEquals(byteArrayOf(1,2,3,4), samples)
                    text(client, """{"type":"audio-ack","sequence":$sequence}""")
                } finally { route.close() }
            }
        } finally { WebSession.stop() }
    }

    @Test fun browserMediaBufferFollowsSavedSettingsLiveAndAfterServiceRestart() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        com.shilapi.xcertplay.AirPlayPersistence.saveMediaBufferMillis(context, 1000)
        WebSession.start(context)
        try {
            handshake().use { client ->
                client.soTimeout = 3000
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                val initial = org.json.JSONObject(frame(client).toString(Charsets.UTF_8))
                assertEquals(1000, initial.getInt("mediaBufferMillis"))
                assertTrue(initial.getBoolean("playback"))
                WebSession.setMediaBufferMillis(context, 500)
                assertEquals(500, org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getInt("mediaBufferMillis"))
                assertEquals(500, com.shilapi.xcertplay.AirPlayPersistence.loadMediaBufferMillis(context))
                WebSession.setBrowserMicrophone(context, false)
                assertEquals(500, org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getInt("mediaBufferMillis"))
            }
            WebSession.stop(); WebSession.start(context)
            handshake().use { client ->
                client.soTimeout = 3000
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                assertEquals(500, org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getInt("mediaBufferMillis"))
            }
        } finally { WebSession.stop() }
    }
    @Test fun plainHttpRejectsMicrophoneCapture() {
        WebSession.start(org.robolectric.RuntimeEnvironment.getApplication())
        try {
            handshake().use { client ->
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                client.soTimeout = 3000
                assertEquals("audio-route", org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getString("type"))
                WebSession.audio.configure(false, true)
                assertFalse(org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getBoolean("microphone"))
                text(client, """{"type":"audio-ready","playback":false,"microphone":true}""")
                assertFalse(WebSession.audio.microphoneRequested)
                val route = WebSession.audio.createRoute()
                try {
                    val config = com.shilapi.xcertplay.airplay.MicrophoneConfig("telephony", 16_000, 1, 97, 20,
                        java.net.InetAddress.getLoopbackAddress(), 12345, ByteArray(32))
                    assertNull(route.microphone(1, config))
                } finally { route.close() }
            }
        } finally { WebSession.stop() }
    }
    @Test fun tlsEndpointUsesThePerInstallationCertificateAndRejectsCrossOriginWebSockets() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        WebSession.start(context)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (WebSession.tls == null && WebSession.tlsError == null && System.nanoTime() < deadline) Thread.sleep(20)
            val endpoint = WebSession.tls ?: error("HTTPS failed: ${WebSession.tlsError}")
            val root = CertificateFactory.getInstance("X.509")
                .generateCertificate(endpoint.certificate.inputStream())
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("wheelplay", root) }
            val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
            val connection = java.net.URL("https://127.0.0.1:8443/").openConnection() as HttpsURLConnection
            connection.sslSocketFactory = ssl.socketFactory
            assertTrue(connection.inputStream.bufferedReader().use { it.readText() }.contains("浏览器麦克风"))
            fun handshake(origin: String): String = (ssl.socketFactory.createSocket("127.0.0.1", 8443) as SSLSocket).use { socket ->
                socket.soTimeout = 3000
                socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                socket.startHandshake()
                socket.outputStream.write(("GET /stream HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:8443\r\nOrigin: $origin\r\n" +
                    "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n").toByteArray())
                readHeaders(socket)
            }
            assertTrue(handshake("http://127.0.0.1:8443").startsWith("HTTP/1.1 403"))
            assertTrue(handshake("https://127.0.0.1:8443").startsWith("HTTP/1.1 101"))
        } finally { WebSession.stop() }
    }
    @Test fun foregroundServiceKeepsEndpointWhenDashboardTaskIsRemoved() {
        val service = org.robolectric.Robolectric.buildService(com.shilapi.xcertplay.DiPlaySessionService::class.java)
            .create().startCommand(0, 1)
        try {
            assertTrue(WebSession.running)
            service.get().onTaskRemoved(android.content.Intent())
            assertTrue(WebSession.running)
            service.get().onStartCommand(android.content.Intent().setAction(
                com.shilapi.xcertplay.DiPlaySessionService.ACTION_STOP), 0, 2)
            assertFalse(WebSession.running)
        } finally { service.destroy() }
    }

    @Test fun retiredMediaSinkCannotReplaceOrClearTheNewSource() {
        WebSession.start(org.robolectric.RuntimeEnvironment.getApplication())
        try {
            val old = WebSession.beginVideo(320, 180)
            val current = WebSession.beginVideo(1280, 720)
            val oldSource = WebVideoSource(320, 180)
            WebSession.attachVideoSource(old, oldSource)
            assertNull(WebSession.videoSource)
            val currentSource = WebVideoSource(1280, 720)
            WebSession.attachVideoSource(current, currentSource)
            assertSame(currentSource, WebSession.videoSource)
            WebSession.endVideo(old)
            assertSame(currentSource, WebSession.videoSource)
            WebSession.endVideo(current)
            assertNull(WebSession.videoSource)
        } finally { WebSession.stop() }
    }

    @Test fun actualHttpWebSocketAndRestart() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        WebSession.start(context)
        try {
            assertTrue(WebSession.error, WebSession.running)
            assertTrue(URL("http://127.0.0.1:8080/").readText().contains("打开即连接"))
            assertTrue(URL("http://127.0.0.1:8080/app.js").readText().contains("pointercancel"))
            handshake("", "http://evil.invalid").use { assertTrue(readHeaders(it).startsWith("HTTP/1.1 403")) }
            handshake().use { client ->
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                assertEquals("audio-route", org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getString("type"))
            }
        } finally { WebSession.stop() }
        assertFalse(WebSession.running)
        WebSession.start(context)
        try { assertTrue("Port must be reusable after stop", WebSession.running) }
        finally { WebSession.stop() }
    }

    @Test fun busyReplyHeartbeatAndProtocolControlsUseTheAsyncWriter() {
        WebSession.start(org.robolectric.RuntimeEnvironment.getApplication())
        try {
            handshake().use { client ->
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                assertEquals("audio-route", org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getString("type"))
                text(client, "{\"type\":\"ping\"}")
                assertEquals("status", org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getString("type"))
                handshake().use { other ->
                    assertTrue(readHeaders(other).startsWith("HTTP/1.1 101"))
                    assertEquals("busy", org.json.JSONObject(frame(other).toString(Charsets.UTF_8)).getString("type"))
                    assertEquals(-1, other.getInputStream().read())
                }
                assertTrue("Rejecting another browser must keep the viewer", WebSession.hasViewer)
                maskedFrame(client, 9, byteArrayOf(1, 2, 3))
                val pong = fi.iki.elonen.NanoWSD.WebSocketFrame.read(client.getInputStream())
                assertEquals(fi.iki.elonen.NanoWSD.WebSocketFrame.OpCode.Pong, pong.opCode)
                assertArrayEquals(byteArrayOf(1, 2, 3), pong.binaryPayload)
                maskedFrame(client, 8, byteArrayOf(3, 0xe8.toByte()))
                val close = fi.iki.elonen.NanoWSD.WebSocketFrame.read(client.getInputStream())
                assertEquals(fi.iki.elonen.NanoWSD.WebSocketFrame.OpCode.Close, close.opCode)
                assertEquals(-1, client.getInputStream().read())
            }
        } finally { WebSession.stop() }
    }

    @Test fun connmanStatusPageAnswersTheTeslaWifiProbeOverHttp() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        WebSession.start(context)
        try {
            val connection = URL("http://127.0.0.1:8080/online/status.html").openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            assertEquals(200, connection.responseCode)
            assertEquals("online", connection.getHeaderField("X-ConnMan-Status"))
            assertEquals("text/html", connection.contentType)
            assertEquals(String(TeslaWifiProbe.STATUS_BODY, Charsets.US_ASCII),
                connection.inputStream.readBytes().toString(Charsets.US_ASCII))
            connection.disconnect()
        } finally { WebSession.stop() }
    }

    private fun handshake(code: String = "", origin: String = "http://127.0.0.1:8080", token: String = "", host: String = "127.0.0.1:8080", port: Int = 8080): Socket = Socket("127.0.0.1", port).apply {
        soTimeout = 3000
        getOutputStream().write(("GET /stream HTTP/1.1\r\nHost: $host\r\n" +
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
        return fi.iki.elonen.NanoWSD.WebSocketFrame.read(socket.getInputStream()).binaryPayload
    }
    private fun text(socket: Socket, message: String) {
        maskedFrame(socket, 1, message.toByteArray())
    }
    private fun maskedFrame(socket: Socket, opcode: Int, bytes: ByteArray) {
        check(bytes.size < 126)
        // RFC6455 client frames are masked; zero mask is sufficient for this protocol fixture.
        socket.getOutputStream().apply {
            write(byteArrayOf((0x80 or opcode).toByte(), (0x80 or bytes.size).toByte(), 0,0,0,0)); write(bytes); flush()
        }
    }
}
