package com.shilapi.xcertplay.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuckDnsApiTest {
    @Test fun addressUpdateCarriesThePrivateAddress() {
        val url = DuckDnsApi.addressUrl("my-car", "token-123", "192.168.43.1")
        assertTrue(url.startsWith("https://www.duckdns.org/update?"))
        assertTrue(url.contains("domains=my-car"))
        assertTrue(url.contains("token=token-123"))
        assertTrue(url.contains("ip=192.168.43.1"))
        assertTrue(url.contains("verbose=true"))
    }

    @Test fun txtRecordIsSetAndClearedWithTheDuckDnsApi() {
        val set = DuckDnsApi.txtUrl("my-car", "token-123", "abc_DEF-123")
        assertTrue(set.contains("txt=abc_DEF-123"))
        val clear = DuckDnsApi.clearTxtUrl("my-car", "token-123")
        assertTrue(clear.contains("txt=&clear=true"))
        assertFalse(clear.contains("abc_DEF-123"))
    }

    @Test fun valuesAreUrlEncoded() {
        val url = DuckDnsApi.txtUrl("my-car", "token", "a b+c")
        assertTrue(url.contains("txt=a+b%2Bc"))
    }
}
