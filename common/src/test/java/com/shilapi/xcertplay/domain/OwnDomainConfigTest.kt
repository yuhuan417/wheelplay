package com.shilapi.xcertplay.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OwnDomainConfigTest {
    private val token = "12345678-1234-1234-1234-123456789abc"

    @Test fun disabledConfigIsAlwaysValid() {
        assertNull(OwnDomainConfig(enabled = false, subdomain = "Bad Name", token = "x").validate())
    }

    @Test fun acceptsALowercaseDuckDnsSubdomain() {
        val config = OwnDomainConfig(enabled = true, subdomain = "my-car", token = token)
        assertNull(config.validate())
        assertEquals("my-car.duckdns.org", config.domain)
    }

    @Test fun rejectsBadSubdomainsAndTokens() {
        assertNotNull(OwnDomainConfig(enabled = true, subdomain = "MyCar", token = token).validate())
        assertNotNull(OwnDomainConfig(enabled = true, subdomain = "my_car", token = token).validate())
        assertNotNull(OwnDomainConfig(enabled = true, subdomain = "mycar", token = "short").validate())
        assertNotNull(OwnDomainConfig(enabled = true, subdomain = "mycar", token = "has space in it").validate())
    }

    @Test fun validatesTheOptionalEmail() {
        assertNull(OwnDomainConfig(enabled = true, subdomain = "mycar", token = token,
            email = "driver@example.com").validate())
        assertNotNull(OwnDomainConfig(enabled = true, subdomain = "mycar", token = token,
            email = "not-an-email").validate())
    }
}
