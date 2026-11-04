package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayMediaEngineTest {
    @Test
    fun streamConnectionIdUsesUnsignedDecimalForHkdfSalt() {
        assertEquals("18446744073709551615", unsignedPlistDecimal(-1L))
        assertEquals(
            BigInteger("18446744073709551615"),
            unsignedPlistInteger(-1L),
        )
    }

    @Test
    fun screenStreamTeardownReportsInactive() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                events += type to active
            }
        }
        val session = testSession()

        try {
            val engine = CarPlayMediaEngine(sink)
            engine.onTeardown(session, 110)
            engine.onTeardown(session, 100)
        } finally {
            session.close()
        }

        assertEquals(listOf(110 to false), events)
    }

    @Test
    fun sessionCloseReportsAllScreenStreamsInactive() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                events += type to active
            }
        }
        val session = testSession()
        val engine = CarPlayMediaEngine(sink)
        val streamsField = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val streams = streamsField.get(engine) as
            MutableMap<CarPlayMediaEngine.StreamKey, Closeable>
        streams[CarPlayMediaEngine.StreamKey(session, 110)] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, 111)] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, 100)] = Closeable {}

        engine.onSessionClosed(session)
        session.close()

        assertEquals(setOf(110 to false, 111 to false), events.toSet())
        assertTrue(streams.isEmpty())
    }

    @Test
    fun screenStreamEndKeepsTheAirPlaySessionAliveForAudio() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val screenEnded = CountDownLatch(1)
        val sink = object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                synchronized(events) { events += type to active }
                if (!active) screenEnded.countDown()
            }
        }
        val sessionEnded = AtomicInteger()
        val session = testSession(
            object : AirPlaySessionListener {
                override fun onSessionEnded(session: AirPlaySession) {
                    sessionEnded.incrementAndGet()
                }
            },
        )
        // The engine derives each stream key from the pair-verify shared secret.
        PairVerify::class.java.getDeclaredField("sharedSecret").apply { isAccessible = true }
            .set(session.pairVerify, ByteArray(32) { 1 })

        try {
            val engine = CarPlayMediaEngine(sink)
            val port = engine.onScreen(
                session,
                type = 110,
                stream = linkedMapOf<String, Any?>("streamConnectionID" to 42L),
            )
            assertTrue("the screen stream must listen", port != null && port > 0)

            // The iPhone dropped its video connection. Audio rides its own UDP ports, so this
            // must not end the AirPlay session.
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port!!), 2_000)
            }

            assertTrue(
                "screen stream teardown must be reported",
                screenEnded.await(5, TimeUnit.SECONDS),
            )
            assertEquals(0, sessionEnded.get())
            assertEquals(listOf(110 to true, 110 to false), events)
        } finally {
            session.close()
        }
    }

    private fun testSession(
        listener: AirPlaySessionListener = object : AirPlaySessionListener {},
    ): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = listener,
        media = object : AirPlayMediaHandler {},
    )
}
