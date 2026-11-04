package com.shilapi.xcertplay.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnDomainRetryTest {
    @Test
    fun transientFailuresAreRecognised() {
        assertTrue(
            OwnDomainManager.isTransientFailure(
                "无法通过 HTTPS 连接 DuckDNS（Unable to resolve host \"www.duckdns.org\": " +
                    "No address associated with hostname）",
            ),
        )
        assertTrue(OwnDomainManager.isTransientFailure("connect timed out"))
        assertTrue(OwnDomainManager.isTransientFailure("java.net.ConnectException: failed"))
    }

    @Test
    fun rejectedTlsAndAcmeFailuresAreNotTransient() {
        assertFalse(
            OwnDomainManager.isTransientFailure(
                "无法通过 HTTPS 连接 DuckDNS（SSL handshake aborted: Connection reset by peer）",
            ),
        )
        assertFalse(OwnDomainManager.isTransientFailure("urn:ietf:params:acme:error:dns"))
    }

    @Test
    fun theFirstRetryIsOneMinuteForBothKinds() {
        assertEquals(60_000L, OwnDomainManager.retryDelayMillis(1, transient = true))
        assertEquals(60_000L, OwnDomainManager.retryDelayMillis(1, transient = false))
    }

    @Test
    fun transientFailuresBackOffGently() {
        assertEquals(120_000L, OwnDomainManager.retryDelayMillis(2, transient = true))
        assertEquals(300_000L, OwnDomainManager.retryDelayMillis(3, transient = true))
        assertEquals(600_000L, OwnDomainManager.retryDelayMillis(4, transient = true))
        // Capped, never longer than the transient ceiling.
        assertEquals(600_000L, OwnDomainManager.retryDelayMillis(9, transient = true))
    }

    @Test
    fun hardFailuresBackOffFurther() {
        assertEquals(300_000L, OwnDomainManager.retryDelayMillis(2, transient = false))
        assertEquals(900_000L, OwnDomainManager.retryDelayMillis(3, transient = false))
        assertEquals(1_800_000L, OwnDomainManager.retryDelayMillis(4, transient = false))
        assertEquals(1_800_000L, OwnDomainManager.retryDelayMillis(12, transient = false))
    }
}
