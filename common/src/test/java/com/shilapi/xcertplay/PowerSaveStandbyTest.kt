package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Looper
import com.shilapi.xcertplay.web.WebSession
import java.time.Duration
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
        private lateinit var originalBluetoothEnabled: (android.content.Context) -> Boolean
        private lateinit var originalSetBluetoothEnabled: (android.content.Context, Boolean) -> Unit
        private lateinit var originalWifiAssociated: (android.content.Context) -> Boolean
        private var originalAssociationTimeoutMillis = 0L

        @JvmStatic
        @org.junit.BeforeClass
        fun captureRootSeams() {
            originalRootAvailable = RootRadioPower.rootAvailable
            originalExecuteRootScript = RootRadioPower.executeRootScript
            originalVerifyRadioOff = RootRadioPower.verifyRadioOff
            originalBluetoothEnabled = RootRadioPower.bluetoothEnabled
            originalSetBluetoothEnabled = RootRadioPower.setBluetoothEnabled
            originalWifiAssociated = RootRadioPower.wifiAssociated
            originalAssociationTimeoutMillis = RootRadioPower.associationTimeoutMillis
        }

        @JvmStatic
        @org.junit.AfterClass
        fun restoreRootSeams() {
            RootRadioPower.rootAvailable = originalRootAvailable
            RootRadioPower.executeRootScript = originalExecuteRootScript
            RootRadioPower.verifyRadioOff = originalVerifyRadioOff
            RootRadioPower.bluetoothEnabled = originalBluetoothEnabled
            RootRadioPower.setBluetoothEnabled = originalSetBluetoothEnabled
            RootRadioPower.wifiAssociated = originalWifiAssociated
            RootRadioPower.associationTimeoutMillis = originalAssociationTimeoutMillis
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
        RootRadioPower.bluetoothEnabled = { true }
        RootRadioPower.setBluetoothEnabled = { _, _ -> }
        RootRadioPower.wifiAssociated = { true }
        RootRadioPower.associationTimeoutMillis = 100L
    }

    @After
    fun tearDown() {
        WebSession.stop()
        CarPlayBackgroundSession.clear()
        DiPlaySessionService.resetStandbyLatch()
        RootRadioPower.suspendedByStandby = false
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
        // The rail has to stay up through the debounce; a one-second pulse must not wake anything.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
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
        // The rail has to stay down past the debounce before the radio is cut; a dip that comes
        // back first must not tear down a live session.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))

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
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertFalse(CarPlayBackgroundSession.powerDisconnected)
        assertTrue(WebSession.running)
        assertEquals("随车通电", wakeTrigger)
        controller.destroy()
    }

    @Test
    fun aBackgroundServiceStartDoesNotWakeTheRadiosAfterAPowerLossStandby() {
        // Field log: a normal service start 21 s after the standby brought Wi-Fi and Bluetooth
        // back up. Robolectric reports the phone as plugged, so only the latch can stop it.
        val controller = Robolectric.buildService(DiPlaySessionService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(app, DiPlaySessionService::class.java), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(WebSession.running)

        CarPlayBackgroundSession.markStandby(
            owner = this,
            wake = {},
            standby = {},
            stop = { it() },
        )
        app.sendBroadcast(Intent(Intent.ACTION_POWER_DISCONNECTED))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))
        assertFalse(WebSession.running)

        CarPlayBackgroundSession.powerDisconnected = false
        val restarted = Robolectric.buildService(DiPlaySessionService::class.java).create().get()
        restarted.onStartCommand(Intent(app, DiPlaySessionService::class.java), 0, 3)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse("a background start must not wake the radios", WebSession.running)
        controller.destroy()
        restarted.stopSelf()
    }

    @Test
    fun aPowerBlipShorterThanTheDebounceDoesNotWakeTheRadios() {
        val controller = Robolectric.buildService(DiPlaySessionService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(app, DiPlaySessionService::class.java), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()

        var wakeTrigger: String? = null
        CarPlayBackgroundSession.markStandby(
            owner = this,
            wake = { trigger -> wakeTrigger = trigger },
            standby = {},
            stop = { it() },
        )
        service.onStartCommand(
            Intent(app, DiPlaySessionService::class.java)
                .setAction(DiPlaySessionService.ACTION_STANDBY),
            0,
            2,
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(WebSession.running)

        // A car charger or a half-seated plug pulses and is gone again: no wake, no radios.
        app.sendBroadcast(Intent(Intent.ACTION_POWER_CONNECTED))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        app.sendBroadcast(Intent(Intent.ACTION_POWER_DISCONNECTED))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))

        assertFalse(WebSession.running)
        assertNull(wakeTrigger)
        controller.destroy()
    }

    @Test
    fun aWakeThatRacesTheSlowStandbyAbortsTheRadioPowerOff() {
        val originalAvailable = RootRadioPower.rootAvailable
        val originalExecute = RootRadioPower.executeRootScript
        val originalVerify = RootRadioPower.verifyRadioOff
        val scripts = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            RootRadioPower.rootAvailable = { true }
            RootRadioPower.verifyRadioOff = { true }
            RootRadioPower.bluetoothEnabled = { true }
            RootRadioPower.setBluetoothEnabled = { _, _ -> }
            RootRadioPower.wifiAssociated = { true }
            RootRadioPower.executeRootScript = { script ->
                scripts.add(script)
                true
            }

            // The layout is 1 s of standby work; the wake arrives in the middle of it.
            RootRadioPower.enterStandby(app)
            RootRadioPower.wakeFromStandby(app)
            Thread.sleep(1_000)

            assertTrue(
                "a standby that lost the race must not cut the radios",
                scripts.none { it.contains("RootHotspotMain stop") },
            )
        } finally {
            RootRadioPower.rootAvailable = originalAvailable
            RootRadioPower.executeRootScript = originalExecute
            RootRadioPower.verifyRadioOff = originalVerify
        }
    }

    @Test
    fun rootRadioPowerTurnsOffHotspotAndWifiOnStandbyAndPowersRadiosOnWake() {
        val originalAvailable = RootRadioPower.rootAvailable
        val originalExecute = RootRadioPower.executeRootScript
        val originalVerify = RootRadioPower.verifyRadioOff
        val scripts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val bluetoothEvents = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val standbyExecuted = java.util.concurrent.CountDownLatch(1)
        val wakeExecuted = java.util.concurrent.CountDownLatch(1)
        try {
            RootRadioPower.rootAvailable = { true }
            RootRadioPower.verifyRadioOff = { true }
            RootRadioPower.bluetoothEnabled = { true }
            RootRadioPower.setBluetoothEnabled = { _, enabled -> bluetoothEvents += enabled }
            RootRadioPower.wifiAssociated = { true }
            RootRadioPower.executeRootScript = { script ->
                scripts.add(script)
                if (script.contains("RootHotspotMain stop")) standbyExecuted.countDown()
                if (script.contains("RootHotspotMain start")) wakeExecuted.countDown()
                true
            }

            RootRadioPower.enterStandby(app)
            val sawStandby = standbyExecuted.await(5, java.util.concurrent.TimeUnit.SECONDS)
            // Let the standby worker finish before waking: this test covers the ordered case, the
            // racing case has its own test below.
            Thread.sleep(300)
            RootRadioPower.wakeFromStandby(app)
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

            // Wi-Fi and Bluetooth come up first; the hotspot waits for the STA association so the
            // AP can be created on the channel the STA already uses.
            val wifiScript = scripts.single { it.contains("cmd wifi set-wifi-enabled enabled") }
            val hotspotScript = scripts.single { it.contains("RootHotspotMain start") }
            assertTrue("Wi-Fi and Bluetooth must start together", wifiScript.contains("svc bluetooth enable"))
            assertTrue("the hotspot must not follow in the first pass", !wifiScript.contains("RootHotspotMain start"))
            assertTrue(hotspotScript.contains("cmd connectivity tether start wifi"))
            assertTrue(
                "the hotspot must start only after Wi-Fi is enabled",
                scripts.indexOf(wifiScript) < scripts.indexOf(hotspotScript),
            )
            assertTrue("standby must disable Bluetooth", false in bluetoothEvents)
            assertTrue("wake must re-enable Bluetooth", true in bluetoothEvents)
        } finally {
            RootRadioPower.rootAvailable = originalAvailable
            RootRadioPower.executeRootScript = originalExecute
            RootRadioPower.verifyRadioOff = originalVerify
            RootRadioPower.suspendedByStandby = false
        }
    }

    @Test
    fun aSecondStandbyRequestIsNotMistakenForAWake() {
        // Field log: the CarPlay teardown asks for standby again while the (slow) power-off script
        // is still running; that used to bump the wake generation, so the teardown "restored" the
        // radios ~21 s after cutting them and Wi-Fi, the hotspot and Bluetooth came back by
        // themselves.
        val originalAvailable = RootRadioPower.rootAvailable
        val originalExecute = RootRadioPower.executeRootScript
        val originalVerify = RootRadioPower.verifyRadioOff
        val originalBluetoothEnabled = RootRadioPower.bluetoothEnabled
        val originalSetBluetooth = RootRadioPower.setBluetoothEnabled
        val wakeScripts = java.util.concurrent.atomic.AtomicInteger()
        val standbyScripts = java.util.concurrent.atomic.AtomicInteger()
        try {
            RootRadioPower.rootAvailable = { true }
            RootRadioPower.verifyRadioOff = { true }
            RootRadioPower.bluetoothEnabled = { false }
            RootRadioPower.setBluetoothEnabled = { _, _ -> }
            RootRadioPower.executeRootScript = { script ->
                if (script.contains("set-wifi-enabled enabled")) wakeScripts.incrementAndGet()
                if (script.contains("RootHotspotMain stop")) {
                    standbyScripts.incrementAndGet()
                    // Still running when the duplicate standby arrives, like the 20 s timeout.
                    Thread.sleep(4_000)
                }
                true
            }

            RootRadioPower.enterStandby(app)
            Thread.sleep(3_500)
            assertEquals(1, standbyScripts.get())
            RootRadioPower.enterStandby(app)
            Thread.sleep(6_000)

            assertEquals("the teardown must not power the radios back on", 0, wakeScripts.get())
        } finally {
            RootRadioPower.rootAvailable = originalAvailable
            RootRadioPower.executeRootScript = originalExecute
            RootRadioPower.verifyRadioOff = originalVerify
            RootRadioPower.bluetoothEnabled = originalBluetoothEnabled
            RootRadioPower.setBluetoothEnabled = originalSetBluetooth
            RootRadioPower.suspendedByStandby = false
            RootRadioPower.lastStandbyRadioOff = null
        }
    }

    @Test
    fun standbyGuardTurnsBluetoothOffAgainWhenOnlyItRebounds() {
        // MIUI restores Bluetooth about 24 s after standby turns it off. Bluetooth has an
        // in-process API, so it must go back down without waiting for a root teardown.
        val originalAvailable = RootRadioPower.rootAvailable
        val originalExecute = RootRadioPower.executeRootScript
        val originalVerify = RootRadioPower.verifyRadioOff
        val originalBluetoothEnabled = RootRadioPower.bluetoothEnabled
        val originalSetBluetooth = RootRadioPower.setBluetoothEnabled
        val standbyScripts = java.util.concurrent.atomic.AtomicInteger()
        val bluetoothDisabled = java.util.concurrent.atomic.AtomicInteger()
        try {
            RootRadioPower.rootAvailable = { true }
            RootRadioPower.verifyRadioOff = { true }
            RootRadioPower.bluetoothEnabled = { false }
            RootRadioPower.setBluetoothEnabled = { _, _ -> }
            RootRadioPower.executeRootScript = { script ->
                if (script.contains("RootHotspotMain stop")) standbyScripts.incrementAndGet()
                true
            }
            @Suppress("DEPRECATION")
            app.getSystemService(android.net.wifi.WifiManager::class.java).isWifiEnabled = false

            val controller = Robolectric.buildService(DiPlaySessionService::class.java).create()
            val service = controller.get()
            service.onStartCommand(Intent(app, DiPlaySessionService::class.java), 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
            CarPlayBackgroundSession.markStandby(owner = this, wake = {}, standby = {}, stop = { it() })
            service.onStartCommand(
                Intent(app, DiPlaySessionService::class.java)
                    .setAction(DiPlaySessionService.ACTION_STANDBY),
                0,
                2,
            )
            val deadline = System.currentTimeMillis() + 5_000
            while (RootRadioPower.lastStandbyRadioOff != true && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertEquals(true, RootRadioPower.lastStandbyRadioOff)
            val teardowns = standbyScripts.get()

            RootRadioPower.bluetoothEnabled = { true }
            RootRadioPower.setBluetoothEnabled = { _, enabled -> if (!enabled) bluetoothDisabled.incrementAndGet() }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))

            assertTrue("the guard has to switch Bluetooth off again", bluetoothDisabled.get() >= 1)
            assertEquals(
                "Bluetooth alone must not trigger the root teardown",
                teardowns,
                standbyScripts.get(),
            )
            controller.destroy()
        } finally {
            RootRadioPower.rootAvailable = originalAvailable
            RootRadioPower.executeRootScript = originalExecute
            RootRadioPower.verifyRadioOff = originalVerify
            RootRadioPower.bluetoothEnabled = originalBluetoothEnabled
            RootRadioPower.setBluetoothEnabled = originalSetBluetooth
            RootRadioPower.suspendedByStandby = false
            RootRadioPower.lastStandbyRadioOff = null
            @Suppress("DEPRECATION")
            app.getSystemService(android.net.wifi.WifiManager::class.java).isWifiEnabled = true
        }
    }

    @Test
    fun standbyGuardCutsTheRadiosAgainWhenSomethingTurnsThemBackOn() {
        val originalAvailable = RootRadioPower.rootAvailable
        val originalExecute = RootRadioPower.executeRootScript
        val originalVerify = RootRadioPower.verifyRadioOff
        val originalBluetoothEnabled = RootRadioPower.bluetoothEnabled
        val originalSetBluetooth = RootRadioPower.setBluetoothEnabled
        val standbyScripts = java.util.concurrent.atomic.AtomicInteger()
        try {
            RootRadioPower.rootAvailable = { true }
            RootRadioPower.verifyRadioOff = { true }
            RootRadioPower.bluetoothEnabled = { false }
            RootRadioPower.setBluetoothEnabled = { _, _ -> }
            RootRadioPower.executeRootScript = { script ->
                if (script.contains("RootHotspotMain stop")) standbyScripts.incrementAndGet()
                true
            }

            val controller = Robolectric.buildService(DiPlaySessionService::class.java).create()
            val service = controller.get()
            service.onStartCommand(Intent(app, DiPlaySessionService::class.java), 0, 1)
            shadowOf(Looper.getMainLooper()).idle()

            CarPlayBackgroundSession.markStandby(
                owner = this,
                wake = {},
                standby = {},
                stop = { it() },
            )
            service.onStartCommand(
                Intent(app, DiPlaySessionService::class.java)
                    .setAction(DiPlaySessionService.ACTION_STANDBY),
                0,
                2,
            )
            val deadline = System.currentTimeMillis() + 5_000
            while (RootRadioPower.lastStandbyRadioOff != true && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertEquals(true, RootRadioPower.lastStandbyRadioOff)
            assertEquals(1, standbyScripts.get())

            // Something outside the app turns Wi-Fi and Bluetooth back on while the phone stays
            // unplugged. Wi-Fi needs the root teardown, so the guard has to run it again.
            RootRadioPower.bluetoothEnabled = { true }
            @Suppress("DEPRECATION")
            app.getSystemService(android.net.wifi.WifiManager::class.java).isWifiEnabled = true
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
            val guardDeadline = System.currentTimeMillis() + 5_000
            while (standbyScripts.get() < 2 && System.currentTimeMillis() < guardDeadline) {
                Thread.sleep(20)
            }
            assertEquals("the guard has to cut the radios again", 2, standbyScripts.get())
            controller.destroy()
        } finally {
            RootRadioPower.rootAvailable = originalAvailable
            RootRadioPower.executeRootScript = originalExecute
            RootRadioPower.verifyRadioOff = originalVerify
            RootRadioPower.bluetoothEnabled = originalBluetoothEnabled
            RootRadioPower.setBluetoothEnabled = originalSetBluetooth
            RootRadioPower.suspendedByStandby = false
            RootRadioPower.lastStandbyRadioOff = null
        }
    }

    @Test
    fun hotspotStillStartsWhenTheStaNeverAssociates() {
        val originalAvailable = RootRadioPower.rootAvailable
        val originalExecute = RootRadioPower.executeRootScript
        val executed = java.util.concurrent.CountDownLatch(1)
        try {
            RootRadioPower.rootAvailable = { true }
            RootRadioPower.wifiAssociated = { false }
            RootRadioPower.associationTimeoutMillis = 100L
            RootRadioPower.executeRootScript = { script ->
                if (script.contains("RootHotspotMain start")) executed.countDown()
                true
            }

            // A power cycle always starts from standby, so the wake runs even when Robolectric
            // reports the radios as already on.
            RootRadioPower.enterStandby(app)
            RootRadioPower.wakeFromStandby(app)

            assertTrue(
                "the hotspot must start even without an uplink",
                executed.await(5, java.util.concurrent.TimeUnit.SECONDS),
            )
        } finally {
            RootRadioPower.rootAvailable = originalAvailable
            RootRadioPower.executeRootScript = originalExecute
            RootRadioPower.suspendedByStandby = false
        }
    }
}
