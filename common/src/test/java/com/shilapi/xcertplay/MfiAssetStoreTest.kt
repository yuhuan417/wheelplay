package com.shilapi.xcertplay

import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Date

class MfiAssetStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = temporary.root
    private val store get() = MfiAssetStore(root)
    private fun identity() = SyntheticMfiIdentity.create()

    private fun install(value: SyntheticMfiIdentity.Identity) = store.installFiles({ value.key.inputStream() }, { value.certificate.inputStream() })
    private fun assertOnlyInstalledResources() {
        assertEquals(listOf("offline-mfi"), root.list()!!.toList())
        assertEquals(setOf("identity.pk8", "certificate.p7b"), File(root, "offline-mfi").list()!!.toSet())
    }

    @Test fun manualImportWorksAndReloadsAfterReplacement() {
        val first = identity(); install(first)
        val previous = store.load()
        val replacement = identity(); install(replacement)
        assertArrayEquals(replacement.certificate, store.load().readCertificate())
        assertArrayEquals(first.certificate, previous.readCertificate())
        assertEquals(64, store.load().signChallenge(ByteArray(32)).size)
        assertOnlyInstalledResources()
    }

    @Test fun mismatchedPairPreservesCurrentResources() {
        val current = identity(); install(current)
        assertThrows(Exception::class.java) {
            store.installFiles({ identity().key.inputStream() }, { identity().certificate.inputStream() })
        }
        assertArrayEquals(current.certificate, store.load().readCertificate())
        assertOnlyInstalledResources()
    }

    @Test fun emptyAndOversizedResourcesLeaveNoPartialInstallation() {
        for (bytes in listOf(byteArrayOf(), ByteArray(MfiAssetStore.MAX_RESOURCE_BYTES.toInt() + 1))) {
            assertThrows(Exception::class.java) { store.installFiles({ bytes.inputStream() }, { identity().certificate.inputStream() }) }
            assertFalse(store.exists())
            assertTrue(root.list()!!.isEmpty())
        }
    }

    @Test fun connectionStartingDuringImportPreventsCommit() {
        val current = identity(); install(current)
        val replacement = identity()
        var checks = 0
        assertThrows(Exception::class.java) {
            store.installFiles({ replacement.key.inputStream() }, { replacement.certificate.inputStream() }) { ++checks == 1 }
        }
        assertEquals(2, checks)
        assertArrayEquals(current.certificate, store.load().readCertificate())
        assertOnlyInstalledResources()
    }

    @Test fun interruptedDirectorySwapRestoresPreviousResources() {
        val current = identity(); install(current)
        assertTrue(File(root, LocalMfiAuthenticationClient.DIRECTORY).renameTo(File(root, "offline-mfi.previous")))
        assertArrayEquals(current.certificate, store.load().readCertificate())
        assertOnlyInstalledResources()
    }

    @Test fun completedDirectorySwapKeepsNewResourcesAndRemovesBackup() {
        val current = identity(); install(current)
        File(root, "offline-mfi").copyRecursively(File(root, "offline-mfi.previous"))
        assertArrayEquals(current.certificate, store.load().readCertificate())
        assertOnlyInstalledResources()
    }

    @Test fun corruptReplacementAfterInterruptedSwapRestoresPreviousResources() {
        val current = identity(); install(current)
        File(root, "offline-mfi").copyRecursively(File(root, "offline-mfi.previous"))
        File(root, "offline-mfi/identity.pk8").writeBytes(byteArrayOf(1))
        assertArrayEquals(current.certificate, store.load().readCertificate())
        assertOnlyInstalledResources()
    }

    @Test fun loadingAfterProcessDeathDeletesAbandonedImportFiles() {
        val current = identity(); install(current)
        File(root, "mfi-import-abandoned.apk").writeBytes(byteArrayOf(1))
        File(root, "mfi-import-abandoned-staging").mkdir()
        assertArrayEquals(current.certificate, store.load().readCertificate())
        assertOnlyInstalledResources()
    }
}

internal object SyntheticMfiIdentity {
    data class Identity(val key: ByteArray, val certificate: ByteArray)

    fun create(): Identity {
        // Ephemeral self-signed test credentials; no accessory resources in the repository.
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val name = X500Name("CN=WheelPlay synthetic import test")
        val certificate = JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(0),
            Date(4102444800000L), name, pair.public)
            .build(JcaContentSignerBuilder("SHA256withECDSA").build(pair.private)).encoded
        return Identity(pair.private.encoded, certificate)
    }

}
