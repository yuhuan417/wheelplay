package com.shilapi.xcertplay.web

import org.junit.Assert.*
import org.junit.Test

class FramePublishSchedulerTest {
    private class Fixture {
        var time = 0L
        var viewing = true
        var latest = 0
        val published = mutableListOf<Int>()
        val tasks = linkedMapOf<Runnable, Long>()
        val scheduler = FramePublishScheduler(
            now = { time }, wanted = { viewing },
            post = { task, delay -> tasks[task] = time + delay }, remove = { tasks.remove(it) },
            publish = { published.add(latest) },
        )
        fun frame(value: Int) { latest = value; scheduler.request() }
        fun advance(to: Long) {
            while (true) {
                val next = tasks.entries.minByOrNull { it.value } ?: break
                if (next.value > to) break
                time = next.value; tasks.remove(next.key); next.key.run()
            }
            time = to
        }
    }

    @Test fun finalFrameInsideThrottleWindowIsPublishedWithoutAnotherArrival() {
        val f = Fixture()
        f.frame(1); f.advance(0)
        f.advance(16); f.frame(2)
        f.advance(32); assertEquals(listOf(1), f.published)
        f.advance(33); assertEquals(listOf(1, 2), f.published)
        f.advance(1000); assertEquals(2, f.published.size)
        assertTrue(f.tasks.isEmpty())
    }

    @Test fun burstKeepsOneTaskAndPublishesOnlyTheLatestTexture() {
        val f = Fixture()
        f.frame(1); f.advance(0)
        for (i in 2..30) { f.advance(i.toLong()); f.frame(i) }
        assertEquals(1, f.tasks.size)
        f.advance(33)
        assertEquals(listOf(1, 30), f.published)
    }

    @Test fun viewerReturningGetsTheLatestStaticFrame() {
        val f = Fixture()
        f.frame(1); f.advance(0)
        f.advance(16); f.frame(2)
        f.viewing = false; f.advance(100)
        f.frame(3); f.advance(200)
        assertEquals(listOf(1), f.published)
        assertTrue(f.tasks.isEmpty())
        f.viewing = true; f.scheduler.request(); f.advance(200)
        assertEquals(listOf(1, 3), f.published)
    }

    @Test fun closingCancelsTheTrailingFrameAndRejectsLateNotifications() {
        val f = Fixture()
        f.frame(1); f.advance(0)
        f.advance(16); f.frame(2); f.scheduler.close()
        f.frame(3); f.advance(1000)
        assertEquals(listOf(1), f.published)
        assertTrue(f.tasks.isEmpty())
    }

    @Test fun pendingStaticFrameResumesAfterAShortViewerGapWithoutRepeatedCompression() {
        val f = Fixture()
        f.frame(1); f.advance(0)
        f.viewing = false; f.advance(100); f.frame(2)
        f.viewing = true; f.scheduler.resume(); f.advance(200)
        assertEquals(listOf(1, 2), f.published)
        repeat(10) { f.scheduler.resume(); f.advance(400L + it * 200) }
        assertEquals(2, f.published.size)
    }

    @Test fun backpressurePausesCaptureAndAckResumesImmediatelyOrAtThrottleBoundary() {
        val f = Fixture()
        f.frame(1); f.advance(0)
        assertEquals(listOf(1), f.published)
        // Downstream is now busy (inFlight = true). New decoder frames arrive but are not compressed.
        f.viewing = false
        f.advance(10); f.frame(2)
        f.advance(25); f.frame(3)
        f.advance(50); f.frame(4)
        assertEquals(listOf(1), f.published)
        assertTrue(f.tasks.isEmpty())
        // ACK arrives at t=50 (>= 33ms after t=0): resumes and publishes immediately at t=50.
        f.viewing = true; f.scheduler.resume(); f.advance(50)
        assertEquals(listOf(1, 4), f.published)
        // Next frame arrives while busy, then ACK arrives early at t=60 (10ms after t=50): waits until t=83 (50 + 33).
        f.viewing = false
        f.advance(55); f.frame(5)
        f.viewing = true; f.scheduler.resume()
        f.advance(82); assertEquals(listOf(1, 4), f.published)
        f.advance(83); assertEquals(listOf(1, 4, 5), f.published)
    }

    @Test fun offscreenEncodeDimensionsCapsLongEdgeAt960AndPreservesEvenAspectRatio() {
        assertEquals(800 to 480, OffscreenVideoOutput.encodeDimensions(800, 480))
        assertEquals(960 to 540, OffscreenVideoOutput.encodeDimensions(1280, 720))
        assertEquals(960 to 540, OffscreenVideoOutput.encodeDimensions(1920, 1080))
        assertEquals(960 to 704, OffscreenVideoOutput.encodeDimensions(1200, 880))
        assertEquals(960 to 704, OffscreenVideoOutput.encodeDimensions(1280, 938))
        assertEquals(704 to 960, OffscreenVideoOutput.encodeDimensions(880, 1200))
    }
}
