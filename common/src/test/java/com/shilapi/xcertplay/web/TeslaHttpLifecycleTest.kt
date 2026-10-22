package com.shilapi.xcertplay.web

import android.content.Intent
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TeslaHttpLifecycleTest {
    @Test fun wirelessAttachDetachAndVpnRevocationNeverAllocateATunnel() {
        val controller = Robolectric.buildService(CarPlayVpnService::class.java).create()
        val service = controller.get()
        try {
            assertEquals(CarPlayVpnService.AttachResult.Started, service.attachWireless(
                java.net.InetAddress.getByName("127.0.0.1"),
                AirPlayConfig("Test", "00:11:22:33:44:55", "00:11:22:33:44:55", "1.0",
                    AirPlayDisplayConfig(1280, 720), port = 0),
                AirPlayIdentity.generate(), PairingStore(), null,
                object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}))
            assertTrue(service.isAttached())
            service.onRevoke()
            assertTrue("Wireless listener is independent of VPN revocation", service.isAttached())
            service.detach()
            assertFalse(service.isAttached())
        } finally { controller.destroy() }
    }

    @Test fun tetheringUsesSystemReportedInterfacesAndRejectsShellInput() {
        val intent = Intent("android.net.conn.TETHER_STATE_CHANGED")
            .putStringArrayListExtra("tetherArray", arrayListOf("ap0", "wlan1", "ap0", "lo", "ap0;id"))
        assertEquals(setOf("ap0", "wlan1"), TeslaHttpCompatibility.tetheredInterfaces(intent))
        assertTrue(TeslaHttpCompatibility.tetheredInterfaces(Intent()).isEmpty())
        assertEquals(setOf("ap0"), TeslaHttpCompatibility.tetheredInterfaces(
            Intent().putExtra("tetherArray", arrayOf("ap0"))))
    }

    @Test fun frameworkVpnBindingUsesTheRevocationBinderAndLocalClientsUseTheLocalBinder() {
        val controller = Robolectric.buildService(CarPlayVpnService::class.java).create()
        try {
            val service = controller.get()
            assertTrue(service.onBind(Intent()) is CarPlayVpnService.LocalBinder)
            assertNotNull(service.onBind(Intent(android.net.VpnService.SERVICE_INTERFACE)))
            assertFalse(service.onBind(Intent(android.net.VpnService.SERVICE_INTERFACE)) is CarPlayVpnService.LocalBinder)
        } finally { controller.destroy() }
    }

}
