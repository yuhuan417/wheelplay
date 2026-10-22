package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.CompressedVideoFrame
import org.junit.Assert.*
import org.junit.Test

class WebVideoSourceTest {
    private val avcc = byteArrayOf(1, 0x64, 0, 0x20, -1, -31, 0, 4, 0x67, 0x64, 0, 0x20, 1, 0, 2, 0x68, 1)

    private fun hvcc() = ByteArray(23).apply {
        this[0] = 1; this[1] = 1; this[12] = 120; this[21] = 3; this[22] = 3
    } + byteArrayOf(32, 0, 1, 0, 2, 64, 1, 33, 0, 1, 0, 2, 66, 1, 34, 0, 1, 0, 2, 68, 1)

    @Test fun invalidConfigurationNeverBecomesAWebCodecsConfig() {
        val source = WebVideoSource()
        source.configure(110, VideoCodec.H265, avcc); assertNull(source.webCodecsConfig)
        source.configure(110, VideoCodec.H264, byteArrayOf(1)); assertNull(source.webCodecsConfig)
    }

    @Test fun hevcConfigurationPreservesProfileTierLevelAndAllParameterSets() {
        val source = WebVideoSource()
        val record = hvcc().apply { this[1] = 34; this[12] = -103 }
        source.configure(110, VideoCodec.H265, record)
        val config = source.webCodecsConfig!!
        assertEquals(VideoCodec.H265, config.codec)
        assertArrayEquals(record, config.codecData)
        source.configure(110, VideoCodec.H265, record.copyOf())
        assertSame(config, source.webCodecsConfig)
        source.configure(110, VideoCodec.H264, avcc)
        assertEquals(VideoCodec.H264, source.webCodecsConfig!!.codec)
        assertNotSame(config, source.webCodecsConfig)
    }

    @Test fun hevcRequiresCompleteValidParameterSets() {
        val source = WebVideoSource()
        for (record in listOf(hvcc().copyOf(30), hvcc().apply { this[22] = 2 },
            hvcc().apply { this[1] = 65 }, hvcc().apply { this[12] = 0 },
            hvcc().apply { this[28] = 66 })) {
            source.configure(110, VideoCodec.H265, record)
            assertNull(source.webCodecsConfig)
        }
    }

    @Test fun webCodecsKeepsTheRawRecordAndForwardsFramesWithKeyframeFlags() {
        val source = WebVideoSource(displayWidth = 1280, displayHeight = 720)
        source.configure(110, VideoCodec.H264, avcc)
        val config = source.webCodecsConfig!!
        assertEquals(110, config.type)
        assertEquals(VideoCodec.H264, config.codec)
        assertEquals(1280, config.width)
        assertEquals(720, config.height)
        assertArrayEquals(avcc, config.codecData)
        val frames = mutableListOf<Triple<ByteArray, Boolean, Long>>()
        val viewer = object : WebVideoSource.WebCodecsConsumer {
            override fun onFrame(frame: CompressedVideoFrame, keyframe: Boolean, ptsUs: Long) {
                frames += Triple(frame.toByteArray(), keyframe, ptsUs)
            }
        }
        assertTrue(source.attachWebCodecs(viewer, config))
        assertTrue(source.frame(110, byteArrayOf(0, 0, 0, 1, 0x41), 900))
        assertTrue(source.frame(110, byteArrayOf(0, 0, 0, 1, 0x65), 1000))
        assertEquals(2, frames.size)
        assertFalse(frames[0].second)
        assertTrue(frames[1].second)
        assertEquals(1000L, frames[1].third)
        source.displayed(viewer)
        source.detachWebCodecs(viewer)
        assertTrue(source.frame(110, byteArrayOf(0, 0, 0, 1, 0x41), 1100))
        assertEquals("A detached viewer receives no more frames", 2, frames.size)
    }

    @Test fun webCodecsRejectsInvalidRecordsAndForgotStreams() {
        val source = WebVideoSource()
        source.configure(110, VideoCodec.H265, avcc)
        assertNull(source.webCodecsConfig)
        source.configure(110, VideoCodec.H264, byteArrayOf(1))
        assertNull(source.webCodecsConfig)
        source.configure(110, VideoCodec.H264, avcc)
        assertNotNull(source.webCodecsConfig)
        source.stopped(110)
        assertNull(source.webCodecsConfig)
    }

    @Test fun recoveryIsRateLimitedAndUsesTheCurrentStream() {
        val source = WebVideoSource()
        var requests = 0
        source.recovery(110) { requests++ }
        source.configure(110, VideoCodec.H264, avcc)
        source.requestKeyframe(); source.requestKeyframe()
        assertEquals(1, requests)
        source.requestKeyframe(force = true); assertEquals(2, requests)
        source.stopped(110); source.requestKeyframe(force = true); assertEquals(2, requests)
    }
}
