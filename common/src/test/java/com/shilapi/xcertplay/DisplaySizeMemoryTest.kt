package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DisplaySizeMemoryTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun lastDisplaySizeRoundTrips() {
        assertNull(AirPlayPersistence.loadLastDisplaySize(context))

        AirPlayPersistence.saveLastDisplaySize(context, 1600, 900)

        assertEquals(1600 to 900, AirPlayPersistence.loadLastDisplaySize(context))
    }

    @Test
    fun unusableDisplaySizesAreRejected() {
        AirPlayPersistence.saveLastDisplaySize(context, 1600, 900)

        AirPlayPersistence.saveLastDisplaySize(context, 0, 0)
        AirPlayPersistence.saveLastDisplaySize(context, 1601, 901)
        AirPlayPersistence.saveLastDisplaySize(context, 100, 900)
        AirPlayPersistence.saveLastDisplaySize(context, 1600, 40)
        AirPlayPersistence.saveLastDisplaySize(context, 8194, 900)

        assertEquals(1600 to 900, AirPlayPersistence.loadLastDisplaySize(context))
    }
}
