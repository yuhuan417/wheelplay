package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.airplay.VideoCodec
import android.util.Log
import com.shilapi.xcertplay.media.CompressedVideoFrame
import com.shilapi.xcertplay.media.HeapVideoFrame
import com.shilapi.xcertplay.media.VideoFrameDecoder
import com.shilapi.xcertplay.media.EncodedVideoSink
import com.shilapi.xcertplay.media.MediaCodecSupport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Owned by one AirPlay session, including the decoder handoff and keyframe recovery. */
internal class WebVideoSource(
    val technology: StreamTechnology,
    private val displayWidth: Int = 0,
    private val displayHeight: Int = 0,
) : EncodedVideoSink {
    val performance = VideoPerformance()
    @Volatile private var processor = "JVM"
    fun performanceSnapshot() = performance.snapshot() + ("processor" to processor)
    private var lastPts: Long? = null

    /** RTC transport parameters derived from the source stream. */
    class Config(val type: Int, val codec: VideoCodec, val profile: String, val parameters: ByteArray) {
        val fmtp get() = if (codec == VideoCodec.H264)
            "profile-level-id=$profile;packetization-mode=1;level-asymmetry-allowed=1" else profile
    }

    /** WebCodecs keeps the original avcC/hvcC record; the browser feeds it to VideoDecoder. */
    class WebCodecsConfig(
        val type: Int,
        val codec: VideoCodec,
        val codecData: ByteArray,
        val width: Int,
        val height: Int,
    )

    /** The current viewer's sink for WebCodecs frames; called on the media thread. */
    internal interface WebCodecsConsumer {
        fun onFrame(frame: CompressedVideoFrame, keyframe: Boolean, ptsUs: Long)
    }

    @Volatile var config: Config? = null
        private set
    @Volatile var webCodecsConfig: WebCodecsConfig? = null
        private set
    @Volatile private var webCodecsConsumer: WebCodecsConsumer? = null
    @Volatile private var consumer: RtcVideoStream? = null
    @Volatile private var displayed = false
    private val recovery = ConcurrentHashMap<Int, () -> Unit>()
    private val lastRequest = AtomicLong(0)
    private var lastConfig: ByteArray? = null
    private var lastConfigType = -1
    private var lastConfigCodec: VideoCodec? = null
    // WebCodecs is a strict transport: a decode failure must stay visible in the diagnostic log
    // instead of being masked by a JPEG fallback.
    val jpegNeeded get() = technology != StreamTechnology.WEBCODECS && !displayed

    override fun createFrameDecoder(key: ByteArray): VideoFrameDecoder? = try {
        NativeVideoDecoder(key, platform = true).also { processor = it.name }
    } catch (e: LinkageError) {
        Log.w("WheelPlayRtc", "Native video processing unavailable; using JVM", e); null
    }

    override fun recovery(type: Int, request: () -> Unit) { recovery[type] = request }

    @Synchronized override fun configure(type: Int, codec: VideoCodec, data: ByteArray) {
        // iPhone repeats codec configuration when honoring forceKeyFrame; this is not a new stream.
        if (lastConfigCodec == codec && lastConfigType == type && lastConfig?.contentEquals(data) == true) return
        detachAll()
        config = null
        webCodecsConfig = null
        lastConfig = null
        lastConfigType = -1
        lastConfigCodec = null
        lastPts = null
        when (technology) {
            StreamTechnology.WEBRTC -> configureRtc(type, codec, data)
            StreamTechnology.WEBCODECS -> configureWebCodecs(type, codec, data)
            StreamTechnology.JPEG -> Unit
        }
    }

    private fun configureRtc(type: Int, codec: VideoCodec, data: ByteArray) {
        if (codec == VideoCodec.H265) {
            config = hevcConfig(type, data)
        } else {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(data)
            if (sps.size < 4 || pps.isEmpty()) return
            val profile = sps.copyOfRange(1, 4).joinToString("") { "%02x".format(it.toInt() and 255) }
            config = Config(type, codec, profile, byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps)
        }
        if (config != null) {
            lastConfig = data.copyOf()
            lastConfigType = type
            lastConfigCodec = codec
        }
    }

    private fun configureWebCodecs(type: Int, codec: VideoCodec, data: ByteArray) {
        val valid = when (codec) {
            VideoCodec.H264 -> {
                val (sps, pps) = MediaCodecSupport.avcParameterSets(data)
                sps.size >= 4 && pps.isNotEmpty()
            }
            VideoCodec.H265 -> hevcConfig(type, data) != null
        }
        if (!valid) return
        webCodecsConfig = WebCodecsConfig(type, codec, data.copyOf(), displayWidth, displayHeight)
        lastConfig = data.copyOf()
        lastConfigType = type
        lastConfigCodec = codec
    }

    private fun hevcConfig(type: Int, data: ByteArray): Config? {
        if (data.size < 23 || data[0].toInt() != 1) return null
        val profileByte = data[1].toInt() and 255
        // WebRTC uses the standard HEVC profile space; do not mislabel vendor profiles.
        if (profileByte ushr 6 != 0 || profileByte and 31 == 0) return null
        val level = data[12].toInt() and 255
        if (level == 0) return null
        val parameters = MediaCodecSupport.hevcCodecSpecificData(data)
        if (parameters.isEmpty()) return null
        var cursor = 23
        val found = mutableSetOf<Int>()
        repeat(data[22].toInt() and 255) {
            if (cursor + 3 > data.size) return null
            val kind = data[cursor++].toInt() and 63
            val count = ((data[cursor].toInt() and 255) shl 8) or (data[cursor + 1].toInt() and 255)
            cursor += 2
            repeat(count) {
                if (cursor + 2 > data.size) return null
                val size = ((data[cursor].toInt() and 255) shl 8) or (data[cursor + 1].toInt() and 255)
                cursor += 2
                if (size < 2 || size > data.size - cursor || (data[cursor].toInt() ushr 1) and 63 != kind) return null
                found += kind
                cursor += size
            }
        }
        if (!found.containsAll(listOf(32, 33, 34))) return null
        val fmtp = "profile-id=${profileByte and 31};tier-flag=${(profileByte ushr 5) and 1};level-id=$level;tx-mode=SRST"
        return Config(type, VideoCodec.H265, fmtp, parameters)
    }

    @Synchronized fun attach(stream: RtcVideoStream, expected: Config): Boolean {
        if (config !== expected) return false
        detach()
        consumer = stream
        return true
    }

    @Synchronized fun attachWebCodecs(viewer: WebCodecsConsumer, expected: WebCodecsConfig): Boolean {
        if (webCodecsConfig !== expected) return false
        webCodecsConsumer = viewer
        return true
    }

    @Synchronized fun displayed(stream: RtcVideoStream) {
        if (consumer === stream && stream.connected) displayed = true
    }

    @Synchronized fun displayed(viewer: WebCodecsConsumer) {
        if (webCodecsConsumer === viewer) displayed = true
    }

    @Synchronized fun detach(stream: RtcVideoStream? = consumer) {
        if (consumer !== stream) return
        val wasDisplayed = displayed
        displayed = false
        consumer = null
        stream?.close()
        if (wasDisplayed) requestKeyframe(force = true)
    }

    @Synchronized fun detachWebCodecs(viewer: WebCodecsConsumer) {
        if (webCodecsConsumer !== viewer) return
        val wasDisplayed = displayed
        displayed = false
        webCodecsConsumer = null
        if (wasDisplayed) requestKeyframe(force = true)
    }

    @Synchronized private fun detachAll() {
        val wasDisplayed = displayed
        displayed = false
        val rtc = consumer
        consumer = null
        rtc?.close()
        webCodecsConsumer = null
        if (wasDisplayed) requestKeyframe(force = true)
    }

    fun requestKeyframe(force: Boolean = false) {
        val now = System.nanoTime() / 1_000_000
        val previous = lastRequest.get()
        if (!force && now - previous < 500) return
        if (!lastRequest.compareAndSet(previous, now)) return
        config?.let { recovery[it.type]?.invoke() }
        webCodecsConfig?.let { recovery[it.type]?.invoke() }
    }

    override fun frame(type: Int, data: ByteArray, presentationTimeUs: Long): Boolean =
        frame(type, data, presentationTimeUs, System.nanoTime(), 0)

    override fun frame(type: Int, data: ByteArray, presentationTimeUs: Long, receivedAtNs: Long, decryptNs: Long): Boolean =
        frame(type, HeapVideoFrame(data), presentationTimeUs, receivedAtNs, decryptNs)

    override fun frame(type: Int, frame: CompressedVideoFrame, pts: Long, receivedAtNs: Long, decryptNs: Long): Boolean {
        val presentationTimeUs = pts
        performance.count("input")
        performance.timing("decrypt", decryptNs)
        performance.timing("receiveToHandoff", System.nanoTime() - receivedAtNs)
        val previous = lastPts
        if (previous != null && presentationTimeUs >= previous) performance.timing("ptsInterval", (presentationTimeUs - previous) * 1000)
        lastPts = presentationTimeUs
        if (technology == StreamTechnology.WEBCODECS) {
            val webCodecs = webCodecsConfig
            if (webCodecs != null && webCodecs.type == type) {
                webCodecsConsumer?.onFrame(frame, frame.isRandomAccess(webCodecs.codec), presentationTimeUs)
            }
            // Never feed the local JPEG decoder in WebCodecs mode, even before a viewer attaches.
            return true
        }
        if (config?.type != type) return false
        consumer?.frame(frame, presentationTimeUs)
        return displayed
    }

    @Synchronized override fun stopped(type: Int) {
        if (config?.type == type || webCodecsConfig?.type == type) {
            detachAll()
            config = null
            webCodecsConfig = null
            lastConfig = null
            lastConfigType = -1
            lastConfigCodec = null
        }
        recovery.remove(type)
    }
}
