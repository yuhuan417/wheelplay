package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WebCodecsWireTest {
    @Test fun configCarriesTheRawParameterSetsAndDisplaySize() {
        val codecData = byteArrayOf(1, 0x64, 0, 0x20, 0xff.toByte())
        val packet = WebCodecsWire.config(110, VideoCodec.H264, 1280, 720, codecData)
        val header = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(WebCodecsWire.MAGIC, header.int)
        assertEquals(WebCodecsWire.TYPE_CONFIG.toByte(), header.get())
        assertEquals(110.toByte(), header.get())
        assertEquals(0.toByte(), header.get())
        assertEquals(0.toByte(), header.get())
        assertEquals(1280, header.int)
        assertEquals(720, header.int)
        assertEquals(1.toByte(), header.get())
        assertEquals(0.toByte(), header.get())
        assertEquals(0.toByte(), header.get())
        assertEquals(0.toByte(), header.get())
        assertArrayEquals(codecData, packet.copyOfRange(WebCodecsWire.CONFIG_HEADER_BYTES, packet.size))
        assertEquals(WebCodecsWire.CONFIG_HEADER_BYTES + codecData.size, packet.size)
    }

    @Test fun frameCarriesKeyframeFlagPtsAndAnnexBPayload() {
        val data = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
        val packet = WebCodecsWire.frame(111, keyframe = true, ptsUs = 123_456, data = data)
        val header = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(WebCodecsWire.MAGIC, header.int)
        assertEquals(WebCodecsWire.TYPE_FRAME.toByte(), header.get())
        assertEquals(111.toByte(), header.get())
        assertEquals(WebCodecsWire.FLAG_KEYFRAME.toByte(), header.get())
        assertEquals(0.toByte(), header.get())
        assertEquals(123_456L, header.long)
        assertArrayEquals(data, packet.copyOfRange(WebCodecsWire.FRAME_HEADER_BYTES, packet.size))
        assertEquals(WebCodecsWire.FRAME_HEADER_BYTES + data.size, packet.size)
    }

    @Test fun hevcHasItsOwnCodecId() {
        val packet = WebCodecsWire.config(110, VideoCodec.H265, 1920, 1080, byteArrayOf(1, 1, 0x60))
        assertEquals(2.toByte(), packet[16])
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyPayloadIsRejected() {
        WebCodecsWire.frame(110, keyframe = false, ptsUs = 0, data = ByteArray(0))
    }
}
