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

    companion object {
        private lateinit var originalRootAvailable: () -> Boolean
        private lateinit var originalExecuteRootScript: (String) -> Boolean
        private lateinit var originalVerifyRadioOff: (android.content.Context) -> Boolean

        @JvmStatic
        @org.junit.BeforeClass
        fun captureRootSeams() {
            originalRootAvailable = RootRadioPower.rootAvailable
            originalExecuteRootScript = RootRadioPower.executeRootScript
            originalVerifyRadioOff = RootRadioPower.verifyRadioOff
        }

        @JvmStatic
        @org.junit.AfterClass
        fun restoreRootSeams() {
            RootRadioPower.rootAvailable = originalRootAvailable
            RootRadioPower.executeRootScript = originalExecuteRootScript
            RootRadioPower.verifyRadioOff = originalVerifyRadioOff
            RootRadioPower.suspendedByStandby = false
        }
    }

    @Before
    fun setUp() {
        val identity = SyntheticMfiIdentity.create()
        MfiAssetStore(app.noBackupFilesDir).installFiles(
            { identity.key.inputStream() },
            { identity.certificate.inputStream() },
        )
        DiPlayBootstrap.ensure(app)
        // Other suites can leave RootAccess.granted=true, which would make the real default seam
        // spawn a blocking `su` on the shared worker thread.
        RootRadioPower.rootAvailable = { false }
        RootRadioPower.executeRootScript = { true }
        RootRadioPower.verifyRadioOff = { true }
    }

    @After
    fun tearDown() {
        WebSession.stop()
        CarPlayBackgroundSession.clear()
        RootRadioPower.suspendedByStandby = false
        RootRadioPower.restoreWifiOnWake = false
        RootRadioPower.restoreHotspotOnWake = false
        RootRadioPower.lastStandbyRadioOff = null
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

    @Test
    fun powerDisconnectedImmediatelyEntersStandbyEvenWhenActiveAndBlocksBluetoothWake() {
        DiPlayPreferences.savePhone(app, "AA:BB:CC:DD:EE:FF", "My iPhone")
        val controller = Robolectric.buildService(DiPlaySessionService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(app, DiPlaySessionService::class.java), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(WebSession.running)

        var standbyReason: String? = null
        var wakeTrigger: String? = null
        CarPlayBackgroundSession.markStandby(
            owner = this,
            wake = { trigger -> wakeTrigger = trigger },
            standby = { reason ->
                standbyReason = reason
                CarPlayBackgroundSession.standby = true
                CarPlayBackgroundSession.active = false
            },
            stop = { it() },
        )
        CarPlayBackgroundSession.active = true
        CarPlayBackgroundSession.standby = false
        assertTrue(CarPlayBackgroundSession.active)

        app.sendBroadcast(Intent(Intent.ACTION_POWER_DISCONNECTED))
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(CarPlayBackgroundSession.powerDisconnected)
        assertEquals("随车断电，已进入省电待机", standbyReason)
        assertFalse(WebSession.running)
        assertNull(ReflectionHelpers.getField<Any?>(service, "wakeLock"))
        assertNull(ReflectionHelpers.getField<Any?>(service, "wifiLock"))

        // While unplugged, Bluetooth ACL connection must NOT wake the service
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val targetIphone = adapter.getRemoteDevice("AA:BB:CC:DD:EE:FF")
        app.sendBroadcast(
            Intent(BluetoothDevice.ACTION_ACL_CONNECTED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, targetIphone),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(WebSession.running)
        assertNull(wakeTrigger)

        // Reconnecting car power wakes everything back up
        app.sendBroadcast(Intent(Intent.ACTION_POWER_CONNECTED))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(CarPlayBackgroundSession.powerDisconnected)
        assertTrue(WebSession.running)
        assertEquals("随车通电", wakeTrigger)
        controller.destroy()
    }

    @Test
    fun rootRadioPowerTurnsOffHotspotAndWifiOnStandbyAndRestoresOnWake() {
        val originalAvailable = RootRadioPower.rootAvailable
        val originalExecute = RootRadioPower.executeRootScript
        val originalVerify = RootRadioPower.verifyRadioOff
        val originalWifiUplink = RootRadioPower.wifiUplinkReady
        val scripts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val standbyExecuted = java.util.concurrent.CountDownLatch(1)
        val wakeExecuted = java.util.concurrent.CountDownLatch(1)
        try {
            RootRadioPower.rootAvailable = { true }
            RootRadioPower.verifyRadioOff = { true }
            RootRadioPower.wifiUplinkReady = { true }
            RootRadioPower.executeRootScript = { script ->
                scripts.add(script)
                if (script.contains("RootHotspotMain stop")) standbyExecuted.countDown()
                if (script.contains("RootHotspotMain start")) wakeExecuted.countDown()
                true
            }

            RootRadioPower.enterStandby(app)
            RootRadioPower.wakeFromStandby(app)
            val sawStandby = standbyExecuted.await(5, java.util.concurrent.TimeUnit.SECONDS)
            val sawWake = wakeExecuted.await(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!sawStandby || !sawWake) {
                System.err.println("DEBUG standby=$sawStandby wake=$sawWake scripts=${scripts.size}:")
                scripts.forEach { System.err.println("DEBUG script>>> " + it.replace("\n", " | ").take(300)) }
            }
            assertTrue(sawStandby)
            assertTrue(sawWake)

            val standbyScript = scripts.first { it.contains("RootHotspotMain stop") }
            assertTrue(standbyScript.contains("cmd wifi stop-softap"))
            assertTrue(standbyScript.contains("cmd wifi set-wifi-enabled disabled"))

            val hotspotIndex = scripts.indexOfFirst { it.contains("RootHotspotMain start") }
            assertTrue("hotspot wake script must exist", hotspotIndex >= 0)
            val wifiIndex = scripts.indexOfFirst { it.contains("cmd wifi set-wifi-enabled enabled") }
            assertTrue("Wi-Fi must be enabled", wifiIndex >= 0)
            assertTrue("Wi-Fi must be enabled before the hotspot starts", wifiIndex < hotspotIndex)
        } finally {
            RootRadioPower.rootAvailable = originalAvailable
            RootRadioPower.executeRootScript = originalExecute
            RootRadioPower.verifyRadioOff = originalVerify
            RootRadioPower.wifiUplinkReady = originalWifiUplink
            RootRadioPower.suspendedByStandby = false
        }
    }
}
