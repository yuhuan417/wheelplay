package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Looper
import com.shilapi.xcertplay.web.WebSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PowerSaveStandbyTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        val identity = SyntheticMfiIdentity.create()
        MfiAssetStore(app.noBackupFilesDir).installFiles(
            { identity.key.inputStream() },
            { identity.certificate.inputStream() },
        )
        DiPlayBootstrap.ensure(app)
    }

    @After
    fun tearDown() {
        WebSession.stop()
        CarPlayBackgroundSession.clear()
    }

    @Test
    fun standbyStopsWebAndReleasesLocksThenPowerConnectWakesEverything() {
        val controller = Robolectric.buildService(DiPlaySessionService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(app, DiPlaySessionService::class.java), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(WebSession.running)
        assertNotNull(ReflectionHelpers.getField<Any?>(service, "wakeLock"))
        assertNotNull(ReflectionHelpers.getField<Any?>(service, "wifiLock"))

        var wakeTrigger: String? = null
        CarPlayBackgroundSession.markStandby(
            owner = this,
            wake = { trigger -> wakeTrigger = trigger },
            standby = {},
            stop = { it() },
        )

        service.onStartCommand(
            Intent(app, DiPlaySessionService::class.java).setAction(DiPlaySessionService.ACTION_STANDBY),
            0,
            2,
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(WebSession.running)
        assertNull(ReflectionHelpers.getField<Any?>(service, "wakeLock"))
        assertNull(ReflectionHelpers.getField<Any?>(service, "wifiLock"))

        app.sendBroadcast(Intent(Intent.ACTION_POWER_CONNECTED))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(WebSession.running)
        assertNotNull(ReflectionHelpers.getField<Any?>(service, "wakeLock"))
        assertNotNull(ReflectionHelpers.getField<Any?>(service, "wifiLock"))
        assertEquals("随车通电", wakeTrigger)
        controller.destroy()
    }

    @Test
    fun bluetoothAclConnectedWakesOnlyForMatchingPairedIphone() {
        DiPlayPreferences.savePhone(app, "AA:BB:CC:DD:EE:FF", "My iPhone")
        val controller = Robolectric.buildService(DiPlaySessionService::class.java).create()
        val service = controller.get()
        service.onStartCommand(
            Intent(app, DiPlaySessionService::class.java).setAction(DiPlaySessionService.ACTION_STANDBY),
            0,
            1,
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(WebSession.running)

        var wakeCount = 0
        CarPlayBackgroundSession.markStandby(
            owner = this,
            wake = { wakeCount += 1 },
            standby = {},
            stop = { it() },
        )

        val adapter = BluetoothAdapter.getDefaultAdapter()
        val otherDevice = adapter.getRemoteDevice("11:22:33:44:55:66")
        app.sendBroadcast(
            Intent(BluetoothDevice.ACTION_ACL_CONNECTED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, otherDevice),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(WebSession.running)
        assertEquals(0, wakeCount)

        val targetIphone = adapter.getRemoteDevice("AA:BB:CC:DD:EE:FF")
        app.sendBroadcast(
            Intent(BluetoothDevice.ACTION_ACL_CONNECTED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, targetIphone),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(WebSession.running)
        assertEquals(1, wakeCount)
        controller.destroy()
    }
}
