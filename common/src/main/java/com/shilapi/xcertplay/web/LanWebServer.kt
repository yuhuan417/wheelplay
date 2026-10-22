package com.shilapi.xcertplay.web

import android.content.Context
import android.os.SystemClock
import com.shilapi.xcertplay.DiagnosticLogSink
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.media.CompressedVideoFrame
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Same-origin HTTP assets and a single backpressured video/control WebSocket. */
internal class LanWebServer(private val context: Context, port: Int = WebListenSettings.DEFAULT_HTTP_PORT,
    private val secure: Boolean = false) : NanoWSD("0.0.0.0", port) {
    init { require(port == 0 || port in 1024..65535) { "Web 监听请使用高位端口；低位端口由 Tesla 兼容映射" } }
    val ready get() = isAlive
    @Volatile private var viewer: Client? = null
    private val timer = Executors.newSingleThreadScheduledExecutor()
    val hasViewer get() = viewer != null
    fun requestHostUi(): Boolean = viewer?.requestHostUi() == true
    fun disconnectViewer() { viewer?.disconnect("设备已移除") }

    override fun createClientHandler(finalAccept: java.net.Socket, inputStream: java.io.InputStream): ClientHandler {
        runCatching {
            finalAccept.tcpNoDelay = true
            finalAccept.trafficClass = 0xB8
        }
        return super.createClientHandler(finalAccept, inputStream)
    }

    init {
        timer.scheduleAtFixedRate({
            val now = SystemClock.elapsedRealtime()
            WebSession.touch.expire(now)
            viewer?.let { if (now - it.lastSeen > 6000 || it.frameStalled(now)) it.disconnect("连接超时") }
        }, 500, 500, TimeUnit.MILLISECONDS)
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.uri == "/stream" && isWebsocketRequested(session)) {
            val origin = session.headers["origin"]
            val sameOrigin = runCatching {
                val parsed = URI(origin ?: "")
                parsed.scheme == (if (secure) "https" else "http") && parsed.rawAuthority.equals(session.headers["host"], true)
            }.getOrDefault(false)
            if (!sameOrigin) return response(Response.Status.FORBIDDEN, "Origin rejected")
            return super.serve(session)
        }
        // Never upgrade arbitrary paths into a control channel.
        if (isWebsocketRequested(session)) return response(Response.Status.NOT_FOUND, "Not found")
        return serveHttp(session)
    }

    override fun serveHttp(session: IHTTPSession): Response {
        if (session.method != Method.GET) return response(Response.Status.METHOD_NOT_ALLOWED, "GET only")
        if (session.uri == TeslaWifiProbe.STATUS_PATH) {
            // ConnMan's plain-HTTP online check: 200 means "this Wi-Fi has internet". The rooted
            // Tesla compatibility routing resolves the probe host names here instead of Tesla.
            android.util.Log.i("WheelPlayTesla", "Wi-Fi internet check from ${session.remoteIpAddress} (${session.headers["host"]})")
            DiagnosticLogSink.append("Wi-Fi 联网检测：HTTP 请求来自 ${session.remoteIpAddress}（${session.headers["host"]}）")
            return newFixedLengthResponse(Response.Status.OK, "text/html",
                TeslaWifiProbe.STATUS_BODY.inputStream(), TeslaWifiProbe.STATUS_BODY.size.toLong()).apply {
                addHeader(TeslaWifiProbe.STATUS_HEADER, TeslaWifiProbe.STATUS_HEADER_VALUE)
                addHeader("Cache-Control", "no-store")
            }
        }
        if (session.uri == "/tls.json") return newFixedLengthResponse(Response.Status.OK, "application/json",
            JSONObject().put("available", WebSession.httpsReady).put("fingerprint", WebSession.tls?.fingerprint)
                .put("port", WebSession.httpsPortFor(context, session.headers["host"])).put("customCertificate", WebSession.tls?.custom == true)
                .put("enabled", WebListenSettings.httpsEnabled(context))
                .put("error", WebSession.tlsError).toString()).apply { addHeader("Cache-Control", "no-store") }
        if (session.uri == "/certificate.crt") {
            val bytes = WebSession.tls?.certificate ?: return response(Response.Status.NOT_FOUND, "HTTPS not ready")
            return newFixedLengthResponse(Response.Status.OK, "application/x-x509-ca-cert", bytes.inputStream(), bytes.size.toLong()).apply {
                addHeader("Content-Disposition", "attachment; filename=wheelplay-certificate.crt")
                addHeader("Cache-Control", "no-store")
            }
        }
        val asset = when (session.uri) {
            "/", "/index.html" -> "index.html" to "text/html; charset=utf-8"
            "/app.js" -> "app.js" to "text/javascript; charset=utf-8"
            "/style.css" -> "style.css" to "text/css; charset=utf-8"
            "/browser-audio.js" -> "browser-audio.js" to "text/javascript; charset=utf-8"
            "/audio-worklet.js" -> "audio-worklet.js" to "text/javascript; charset=utf-8"
            else -> return response(Response.Status.NOT_FOUND, "Not found")
        }
        return context.assets.open("web/${asset.first}").use {
            val bytes = it.readBytes()
            newFixedLengthResponse(Response.Status.OK, asset.second, bytes.inputStream(), bytes.size.toLong()).apply {
                addHeader("Cache-Control", "no-store")
                addHeader("X-Content-Type-Options", "nosniff")
                addHeader("Referrer-Policy", "no-referrer")
                addHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; media-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
            }
        }
    }

    private fun response(status: Response.Status, text: String) =
        newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, text).apply { addHeader("Cache-Control", "no-store") }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = Client(handshake)

    override fun stop() {
        viewer?.disconnect("服务已停止")
        timer.shutdownNow()
        super.stop()
    }

    private inner class Client(handshake: IHTTPSession) : WebSocket(handshake) {
        @Volatile var lastSeen = SystemClock.elapsedRealtime()
        fun frameStalled(now: Long) = sender.frameStalled(now)
        fun requestHostUi(): Boolean {
            if (ended.get() || closing.get() || viewer !== this) return false
            reply(WebStreamSender.Control.HOST_UI, """{"type":"host-ui"}""")
            return true
        }
        private val ended = AtomicBoolean(false)
        private val closing = AtomicBoolean(false)
        private val sender = WebStreamSender(
            write = { super.sendFrame(it) },
            onFailure = { disconnect("串流连接中断") },
            onVideoBacklog = {
                WebSession.videoSource?.requestKeyframe(force = true)
                logOnce("webcodecs-backlog") {
                    "WebCodecs backlog: queued frames dropped while the socket was full; requesting a keyframe"
                }
            },
        )
        private val videoLock = Any()
        private var webCodecsSource: WebVideoSource? = null
        private var webCodecsConfig: WebVideoSource.WebCodecsConfig? = null
        private var webCodecs: WebCodecsViewer? = null
        private var webCodecsAttempted = false
        private var webCodecsActive = false
        private var webCodecsError: String? = null
        private val loggedOnce = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        private fun reconcileVideo() = synchronized(videoLock) {
            val source = WebSession.videoSource
            val nextConfig = source?.webCodecsConfig
            val sourceChanged = webCodecsSource !== source
            val configChanged = webCodecsConfig !== nextConfig
            if (sourceChanged || configChanged) {
                stopWebCodecs(
                    reason = "sourceChanged=$sourceChanged configChanged=$configChanged " +
                        "type=${nextConfig?.type} codec=${nextConfig?.codec}",
                )
                webCodecsSource = source; webCodecsConfig = nextConfig; webCodecsAttempted = false
                webCodecsError = null
            }
        }

        private inner class WebCodecsViewer(
            val source: WebVideoSource,
            val config: WebVideoSource.WebCodecsConfig,
        ) : WebVideoSource.WebCodecsConsumer {
            @Volatile var detached = false
            @Volatile var frames = 0L
            override fun onFrame(frame: CompressedVideoFrame, keyframe: Boolean, ptsUs: Long) {
                if (detached || ended.get() || closing.get()) return
                frames += 1
                sender.video(WebCodecsWire.frame(config.type, keyframe, ptsUs, frame.toByteArray()), keyframe)
            }
        }

        private fun startWebCodecs() = synchronized(videoLock) {
            reconcileVideo()
            if (webCodecsAttempted || ended.get() || closing.get() || !WebSession.videoActive) return
            // WebCodecs is only exposed in a secure context, so the plain-HTTP listener cannot use it.
            if (!this@LanWebServer.secure) {
                logOnce("webcodecs-http") {
                    "WebCodecs requires HTTPS; the plain-HTTP listener does not carry video"
                }
                return
            }
            val source = webCodecsSource ?: return
            val config = webCodecsConfig ?: return
            webCodecsAttempted = true
            webCodecsError = null
            val viewer = WebCodecsViewer(source, config)
            if (!source.attachWebCodecs(viewer, config)) { webCodecsAttempted = false; return }
            WebSession.webCodecsActive = false
            webCodecs = viewer
            sender.videoConfig(WebCodecsWire.config(config.type, config.codec, config.width, config.height, config.codecData))
            source.requestKeyframe(force = true)
            com.shilapi.xcertplay.DiagnosticLogSink.append(
                "WebCodecs start: codec=${config.codec} size=${config.width}x${config.height} " +
                    "stream=${config.type} configBytes=${config.codecData.size} secure=${this@LanWebServer.secure}",
            )
        }

        private fun stopWebCodecs(
            notifyBrowser: Boolean = true,
            reason: String = "requested",
        ) = synchronized(videoLock) {
            com.shilapi.xcertplay.DiagnosticLogSink.append(
                "WebCodecs stop: $reason notify=$notifyBrowser",
            )
            val old = webCodecs
            webCodecs = null
            webCodecsActive = false
            WebSession.webCodecsActive = false
            old?.detached = true
            if (old != null) {
                webCodecsSource?.detachWebCodecs(old)
                if (notifyBrowser && !ended.get() && !closing.get()) {
                    reply(WebStreamSender.Control.WEBCODECS, """{"type":"webcodecs-stop"}""")
                }
            }
        }

        /**
         * Keeps a WebCodecs failure in the diagnostic report and leaves the viewer stopped.
         * There is no fallback transport, so the failure must stay visible.
         */
        private fun failWebCodecs(reason: String, detail: String? = null) = synchronized(videoLock) {
            val viewer = webCodecs
            webCodecs = null
            webCodecsActive = false
            WebSession.webCodecsActive = false
            webCodecsAttempted = true
            viewer?.detached = true
            if (viewer != null) webCodecsSource?.detachWebCodecs(viewer)
            webCodecsError = reason
            val report = buildString {
                append("WebCodecs failed: ").append(reason)
                webCodecsConfig?.let {
                    append("; codec=").append(it.codec)
                    append(" size=").append(it.width).append('x').append(it.height)
                    append(" configBytes=").append(it.codecData.size)
                    append(" stream=").append(it.type)
                }
                append("; submittedFrames=").append(viewer?.frames ?: 0)
                append("; secure=").append(this@LanWebServer.secure)
                if (!detail.isNullOrBlank()) append("; detail=").append(detail.take(6000))
            }
            com.shilapi.xcertplay.DiagnosticLogSink.append(report)
            android.util.Log.w("WheelPlayVideo", report)
            if (!ended.get() && !closing.get()) {
                reply(WebStreamSender.Control.WEBCODECS,
                    JSONObject().put("type", "webcodecs-error").put("reason", reason).toString())
            }
        }

        private fun logOnce(key: String, message: () -> String) {
            if (!loggedOnce.add(key)) return
            com.shilapi.xcertplay.DiagnosticLogSink.append(message())
        }

        private fun reply(kind: WebStreamSender.Control, text: String, key: Any = kind, afterSend: () -> Unit = {}) {
            sender.control(kind, WebSocketFrame(WebSocketFrame.OpCode.Text, true, text), key, afterSend)
        }

        // NanoWSD also replies to RFC6455 Ping/Close on its reader thread. Route those writes too.
        override fun sendFrame(frame: WebSocketFrame) {
            when (frame.opCode) {
                WebSocketFrame.OpCode.Pong -> sender.control(WebStreamSender.Control.PONG, frame)
                WebSocketFrame.OpCode.Close -> sender.control(WebStreamSender.Control.CLOSE, frame) {
                    disconnect("连接已关闭")
                }
                else -> error("Application frames must use the stream sender")
            }
        }

        override fun close(code: WebSocketFrame.CloseCode, reason: String, initiatedByRemote: Boolean) {
            // Keep the reader alive until the queued close reply is written; NanoWSD's default
            // close changes state immediately and would close the socket before this async write.
            if (closing.compareAndSet(false, true)) sendFrame(WebSocketFrame.CloseFrame(code, reason))
        }

        override fun onOpen() {
            synchronized(this@LanWebServer) {
                if (viewer != null || !WebSession.touch.claim(this)) {
                    reply(WebStreamSender.Control.BUSY,
                        """{"type":"busy","message":"已有车机连接，请先在另一台车机断开"}""") {
                        disconnect("已有车机连接")
                    }
                    return
                }
                viewer = this
                WebSession.viewerStateChanged()
                WebSession.audio.attach(this, secure, { packet, sequence -> sender.audio(packet, sequence) }, { text ->
                    val data = JSONObject(text)
                    val kind = when (data.getString("type")) {
                        "mic-config" -> WebStreamSender.Control.MIC_CONFIG
                        "mic-stop" -> WebStreamSender.Control.MIC_STOP
                        "audio-route" -> {
                            sender.configureAudioBuffer(data.getInt("mediaBufferMillis"))
                            if (!data.getBoolean("playback")) sender.clearAudio()
                            WebStreamSender.Control.AUDIO_ROUTE
                        }
                        "audio-stop" -> {
                            sender.clearAudioStream(data.getInt("stream"))
                            WebStreamSender.Control.AUDIO_STOP
                        }
                        else -> { sender.clearAudio(); WebStreamSender.Control.AUDIO_RESET }
                    }
                    reply(kind, text, if (kind == WebStreamSender.Control.AUDIO_STOP) kind to data.getInt("stream") else kind)
                })
            }
        }

        override fun onMessage(message: WebSocketFrame) {
            if (ended.get() || closing.get() || viewer !== this) return
            try {
                if (message.opCode == WebSocketFrame.OpCode.Binary) {
                    require(message.binaryPayload.size <= 20_000)
                    lastSeen = SystemClock.elapsedRealtime()
                    WebSession.audio.input(this, message.binaryPayload)
                    return
                }
                require(message.opCode == WebSocketFrame.OpCode.Text && message.binaryPayload.size <= 32768)
                val data = JSONObject(message.textPayload)
                val textType = data.optString("type")
                // WebCodecs failures carry the browser's diagnostics, not just a one-line reason.
                val textLimit = when (textType) {
                    "webcodecs-fallback", "webcodecs-unsupported" -> 16_384
                    else -> 2_048
                }
                require(message.binaryPayload.size <= textLimit)
                lastSeen = SystemClock.elapsedRealtime()
                when (data.getString("type")) {
                    "audio-ready" -> {
                        val playback = data.getBoolean("playback")
                        val microphone = data.getBoolean("microphone")
                        if (!playback) sender.clearAudio()
                        WebSession.audio.ready(this, playback, microphone)
                    }
                    "audio-ack" -> {
                        val sequence = data.getLong("sequence")
                        require(sequence in 0..0xffff_ffffL)
                        sender.acknowledgeAudio(sequence)
                    }
                    "phone-connect" -> WebSession.requestPhoneConnection { result ->
                        if (viewer === this && !ended.get() && !closing.get()) {
                            reply(WebStreamSender.Control.PHONE_CONNECT,
                                JSONObject().put("type", "phone-connect-result").put("message", result).toString())
                        }
                    }
                    "viewport" -> {
                        val width = data.getInt("width")
                        val height = data.getInt("height")
                        require(WebSession.reportBrowserViewport(width, height))
                    }
                    "ping" -> {
                        reconcileVideo()
                        if (WebSession.videoActive) {
                            if (!this@LanWebServer.secure) {
                                logOnce("webcodecs-http-status") {
                                    "WebCodecs requires HTTPS but this viewer is on HTTP"
                                }
                            } else if (webCodecsConfig == null) {
                                logOnce("webcodecs-no-config") {
                                    "WebCodecs selected but no valid avcC/hvcC configuration has arrived"
                                }
                            }
                        }
                        synchronized(videoLock) { reply(WebStreamSender.Control.STATUS,
                            JSONObject().put("type", "status").put("width", WebSession.width)
                            .put("height", WebSession.height).put("stage", WebSession.stage)
                            .put("streaming", WebSession.videoActive && webCodecsActive)
                            .put("webCodecsAvailable", webCodecsConfig != null && !webCodecsAttempted &&
                                WebSession.videoActive && this@LanWebServer.secure)
                            .put("webCodecsNeedsHttps", webCodecsConfig != null && WebSession.videoActive &&
                                !this@LanWebServer.secure)
                            .put("webCodecsActive", webCodecsActive)
                            .put("webCodecsFrames", webCodecs?.frames ?: 0)
                            .put("webCodecsError", webCodecsError)
                            .put("performance", JSONObject().put("source",
                                WebSession.videoSource?.performanceSnapshot()?.let(::JSONObject)))
                            .put("transport", when {
                                webCodecsError != null -> "webcodecs-failed"
                                webCodecsActive -> "webcodecs"
                                else -> "webcodecs-pending"
                            })
                            .toString()) }
                    }
                    "webcodecs-start" -> startWebCodecs()
                    "webcodecs-keyframe" -> synchronized(videoLock) {
                        if (webCodecs != null) webCodecsSource?.requestKeyframe(force = true)
                    }
                    "webcodecs-ready" -> synchronized(videoLock) {
                        val viewer = webCodecs ?: return@synchronized
                        webCodecsActive = true
                        WebSession.webCodecsActive = true
                        webCodecsSource?.displayed(viewer)
                        com.shilapi.xcertplay.DiagnosticLogSink.append(
                            "WebCodecs video displayed by the browser (${viewer.config.codec})",
                        )
                    }
                    "webcodecs-fallback" -> {
                        val reason = data.optString("reason").take(200).ifBlank { "decoder unavailable" }
                        val detail = data.optJSONObject("detail")?.toString()
                        // Keep the failure and its diagnostics visible; there is no fallback transport.
                        failWebCodecs(reason, detail)
                    }
                    "webcodecs-unsupported" -> {
                        val reason = data.optString("reason").take(120).ifBlank { "VideoDecoder unavailable" }
                        val detail = data.optJSONObject("detail")?.toString()
                        failWebCodecs("browser unsupported: $reason", detail)
                    }
                    "touch" -> {
                        val contacts = data.getJSONArray("contacts")
                        require(contacts.length() <= 2)
                        val list = (0 until contacts.length()).map {
                            val point = contacts.getJSONObject(it)
                            AirPlayContact(point.getInt("id"), point.getDouble("x"), point.getDouble("y"), point.getBoolean("down"))
                        }
                        if (!WebSession.touch.touch(this, list, lastSeen)) {
                            reply(WebStreamSender.Control.INPUT_UNAVAILABLE, """{"type":"input-unavailable"}""")
                        }
                    }
                    else -> error("Unknown message")
                }
            } catch (_: Exception) { disconnect("无效的控制消息") }
        }

        override fun onClose(code: WebSocketFrame.CloseCode, reason: String, initiatedByRemote: Boolean) = cleanup()
        override fun onException(exception: IOException) = cleanup()
        override fun onPong(pong: WebSocketFrame) { lastSeen = SystemClock.elapsedRealtime() }

        fun disconnect(reason: String) {
            cleanup()
            // Force close: no blocking close handshake on the watchdog/service thread.
            runCatching { handshakeRequest.inputStream.close() }
        }

        private fun cleanup() {
            if (!ended.compareAndSet(false, true)) return
            stopWebCodecs(notifyBrowser = false)
            synchronized(this@LanWebServer) {
                if (viewer === this) { WebSession.audio.detach(this); viewer = null }
                WebSession.touch.drop(this)
            }
            sender.close()
            WebSession.viewerStateChanged()
        }
    }
}
