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

    @Volatile var webCodecsConfig: WebCodecsConfig? = null
        private set
    @Volatile private var webCodecsConsumer: WebCodecsConsumer? = null
    @Volatile private var displayed = false
    private val recovery = ConcurrentHashMap<Int, () -> Unit>()
    private val lastRequest = AtomicLong(0)
    private var lastConfig: ByteArray? = null
    private var lastConfigType = -1
    private var lastConfigCodec: VideoCodec? = null
    // WebCodecs is a strict transport: a decode failure must stay visible in the diagnostic log
    // instead of being masked by a JPEG fallback.
    val jpegNeeded get() = technology != StreamTechnology.WEBCODECS

    override fun createFrameDecoder(key: ByteArray): VideoFrameDecoder? = try {
        NativeVideoDecoder(key, platform = true).also { processor = it.name }
    } catch (e: LinkageError) {
        Log.w("WheelPlayVideo", "Native video processing unavailable; using JVM", e); null
    }

    override fun recovery(type: Int, request: () -> Unit) { recovery[type] = request }

    @Synchronized override fun configure(type: Int, codec: VideoCodec, data: ByteArray) {
        // iPhone repeats codec configuration when honoring forceKeyFrame; this is not a new stream.
        if (lastConfigCodec == codec && lastConfigType == type && lastConfig?.contentEquals(data) == true) return
        detachAll()
        webCodecsConfig = null
        lastConfig = null
        lastConfigType = -1
        lastConfigCodec = null
        lastPts = null
        if (technology == StreamTechnology.WEBCODECS) configureWebCodecs(type, codec, data)
    }

    private fun configureWebCodecs(type: Int, codec: VideoCodec, data: ByteArray) {
        val valid = when (codec) {
            VideoCodec.H264 -> {
                val (sps, pps) = MediaCodecSupport.avcParameterSets(data)
                sps.size >= 4 && pps.isNotEmpty()
            }
            VideoCodec.H265 -> validHevcConfig(data)
        }
        if (!valid) return
        webCodecsConfig = WebCodecsConfig(type, codec, data.copyOf(), displayWidth, displayHeight)
        lastConfig = data.copyOf()
        lastConfigType = type
        lastConfigCodec = codec
    }

    private fun validHevcConfig(data: ByteArray): Boolean {
        if (data.size < 23 || data[0].toInt() != 1) return false
        val profileByte = data[1].toInt() and 255
        // Standard HEVC profile space only; do not mislabel vendor profiles.
        if (profileByte ushr 6 != 0 || profileByte and 31 == 0) return false
        val level = data[12].toInt() and 255
        if (level == 0) return false
        if (MediaCodecSupport.hevcCodecSpecificData(data).isEmpty()) return false
        var cursor = 23
        val found = mutableSetOf<Int>()
        repeat(data[22].toInt() and 255) {
            if (cursor + 3 > data.size) return false
            val kind = data[cursor++].toInt() and 63
            val count = ((data[cursor].toInt() and 255) shl 8) or (data[cursor + 1].toInt() and 255)
            cursor += 2
            repeat(count) {
                if (cursor + 2 > data.size) return false
                val size = ((data[cursor].toInt() and 255) shl 8) or (data[cursor + 1].toInt() and 255)
                cursor += 2
                if (size < 2 || size > data.size - cursor || (data[cursor].toInt() ushr 1) and 63 != kind) return false
                found += kind
                cursor += size
            }
        }
        return found.containsAll(listOf(32, 33, 34))
    }

    @Synchronized fun attachWebCodecs(viewer: WebCodecsConsumer, expected: WebCodecsConfig): Boolean {
        if (webCodecsConfig !== expected) return false
        detachAll()
        webCodecsConsumer = viewer
        return true
    }

    @Synchronized fun displayed(viewer: WebCodecsConsumer) {
        if (webCodecsConsumer === viewer) displayed = true
    }

    @Synchronized fun detachWebCodecs(viewer: WebCodecsConsumer) {
        if (webCodecsConsumer !== viewer) return
        val wasDisplayed = displayed
        displayed = false
        webCodecsConsumer = null
        if (wasDisplayed) requestKeyframe(force = true)
    }

    @Synchronized fun detach() = detachAll()

    @Synchronized private fun detachAll() {
        val wasDisplayed = displayed
        displayed = false
        webCodecsConsumer = null
        if (wasDisplayed) requestKeyframe(force = true)
    }

    fun requestKeyframe(force: Boolean = false) {
        val now = System.nanoTime() / 1_000_000
        val previous = lastRequest.get()
        if (!force && now - previous < 500) return
        if (!lastRequest.compareAndSet(previous, now)) return
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
        return false
    }

    @Synchronized override fun stopped(type: Int) {
        if (webCodecsConfig?.type == type) {
            detachAll()
            webCodecsConfig = null
            lastConfig = null
            lastConfigType = -1
            lastConfigCodec = null
        }
        recovery.remove(type)
    }
}
