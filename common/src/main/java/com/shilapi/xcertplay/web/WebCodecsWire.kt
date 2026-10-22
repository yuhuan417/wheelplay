package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.airplay.VideoCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary WebCodecs messages multiplexed on the paired control WebSocket next to PCM audio.
 *
 * Both message kinds start with the magic so the browser can tell them apart from audio packets.
 * All integers are little-endian to match the audio wire format.
 *
 * Config: [magic u32][type=1 u8][streamType u8][flags u8][reserved u8]
 *         [width u32][height u32][codec u8][reserved 3 bytes][avcC/hvcC record]
 * Frame:  [magic u32][type=2 u8][streamType u8][flags u8][reserved u8]
 *         [ptsUs u64][Annex B NAL units]
 */
internal object WebCodecsWire {
    const val MAGIC = 0x31435657 // "WVC1"
    const val TYPE_CONFIG = 1
    const val TYPE_FRAME = 2
    const val FLAG_KEYFRAME = 1
    const val FRAME_HEADER_BYTES = 16
    const val CONFIG_HEADER_BYTES = 20

    fun config(type: Int, codec: VideoCodec, width: Int, height: Int, codecData: ByteArray): ByteArray {
        require(type in 0..255) { "stream type out of range" }
        require(codecData.isNotEmpty()) { "codec data must not be empty" }
        val buffer = ByteBuffer.allocate(CONFIG_HEADER_BYTES + codecData.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.put(TYPE_CONFIG.toByte())
        buffer.put(type.toByte())
        buffer.put(0)
        buffer.put(0)
        buffer.putInt(width.coerceAtLeast(0))
        buffer.putInt(height.coerceAtLeast(0))
        buffer.put(codecId(codec))
        buffer.put(0); buffer.put(0); buffer.put(0)
        buffer.put(codecData)
        return buffer.array()
    }

    fun frame(type: Int, keyframe: Boolean, ptsUs: Long, data: ByteArray): ByteArray {
        require(type in 0..255) { "stream type out of range" }
        require(data.isNotEmpty()) { "frame data must not be empty" }
        val buffer = ByteBuffer.allocate(FRAME_HEADER_BYTES + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.put(TYPE_FRAME.toByte())
        buffer.put(type.toByte())
        buffer.put(if (keyframe) FLAG_KEYFRAME.toByte() else 0)
        buffer.put(0)
        buffer.putLong(ptsUs)
        buffer.put(data)
        return buffer.array()
    }

    private fun codecId(codec: VideoCodec): Byte = when (codec) {
        VideoCodec.H264 -> 1
        VideoCodec.H265 -> 2
    }
}
