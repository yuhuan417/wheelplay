package com.shilapi.xcertplay.web

import android.os.ParcelFileDescriptor
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.network.TeslaHttpConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TeslaVpnLifecycleTest {
    class TestService : CarPlayVpnService() {
        val allocations = mutableListOf<Pair<String, ParcelFileDescriptor>>()
        val duplicates = mutableListOf<ParcelFileDescriptor>()
        val peers = mutableListOf<ParcelFileDescriptor>()
        var consent = true
        var addressPresent = true
        var failAddress: String? = null
        var failDup = false
        override fun isTeslaAddressPresent(address: String) = addressPresent
        private fun allocate(address: String): ParcelFileDescriptor {
            if (address == failAddress) throw IOException("allocation failed")
            return ParcelFileDescriptor.createPipe().let { pipe ->
                peers.add(pipe[1]); allocations.add(address to pipe[0]); pipe[0]
            }
        }
        override fun establishTeslaTunnel(address: String): ParcelFileDescriptor {
            if (!consent) throw IOException("VPN 未获授权")
            return allocate(address)
        }
        override fun duplicateTeslaTunnel(fd: ParcelFileDescriptor): ParcelFileDescriptor {
            if (failDup) throw IOException("dup failed")
            return super.duplicateTeslaTunnel(fd).also(duplicates::add)
        }
    }

    private lateinit var controller: ServiceController<TestService>
    private lateinit var service: TestService
    private val address = TeslaHttpConfig.DEFAULT_VPN_ADDRESS
    private val placeholder = TeslaHttpConfig.VPN_PLACEHOLDER_ADDRESS
    @Before fun create() {
        controller = Robolectric.buildService(TestService::class.java).create()
        service = controller.get()
    }
    @After fun destroy() {
        controller.destroy()
        service.allocations.map { it.second }.plus(service.duplicates).plus(service.peers).forEach { runCatching { it.close() } }
    }
    private fun assertClosed(fd: ParcelFileDescriptor) {
        assertTrue("FD must be closed", runCatching { fd.fd }.isFailure)
    }
    private fun assertOpen(fd: ParcelFileDescriptor) { assertTrue(fd.fd >= 0) }

    @Test fun retainsBothAnchorReferencesAndReusesTheUnchangedConfiguration() {
        assertEquals(address, service.setTeslaAddress(address).address)
        assertEquals(listOf(address, placeholder), service.allocations.map { it.first })
        assertEquals(1, service.duplicates.size)
        repeat(3) { service.setTeslaAddress(address); service.detach() }
        assertEquals(2, service.allocations.size)
        service.allocations.forEach { assertOpen(it.second) }
        service.duplicates.forEach(::assertOpen)
    }

    @Test fun partialEstablishmentFailureClosesOriginalAndDuplicateAndDoesNotAutomaticallyRetry() {
        service.failAddress = placeholder
        assertNull(service.setTeslaAddress(address).address)
        service.allocations.forEach { assertClosed(it.second) }
        service.duplicates.forEach(::assertClosed)
        service.failAddress = null
        repeat(3) { service.setTeslaAddress(address); service.detach() }
        assertEquals(1, service.allocations.size)
        assertEquals(address, service.setTeslaAddress(address, retry = true).address)
    }

    @Test fun duplicateFailureClosesTheOriginalAndNeverCreatesPlaceholder() {
        service.failDup = true
        assertNull(service.setTeslaAddress(address).address)
        assertEquals(listOf(address), service.allocations.map { it.first })
        assertClosed(service.allocations.single().second)
    }

    @Test fun revokeClosesAllReferencesAndNeverReestablishesUntilExplicitAuthorizedRetry() {
        service.setTeslaAddress(address)
        service.onRevoke()
        service.allocations.forEach { assertClosed(it.second) }
        service.duplicates.forEach(::assertClosed)
        val count = service.allocations.size
        service.setTeslaAddress(address)
        service.detach()
        assertEquals(count, service.allocations.size)
        service.consent = false
        assertNull(service.setTeslaAddress(address, retry = true).address)
        assertEquals(count, service.allocations.size)
        service.consent = true
        assertEquals(address, service.setTeslaAddress(address, retry = true).address)
    }

    @Test fun destroyClosesAllReferencesAndDoesNotRestorePlaceholder() {
        service.setTeslaAddress(address)
        val count = service.allocations.size
        controller.destroy()
        assertEquals(count, service.allocations.size)
        service.allocations.forEach { assertClosed(it.second) }
        service.duplicates.forEach(::assertClosed)
    }
}
