package com.gamesuite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayUrlValidatorTest {
    private fun err(s: String) = (RelayUrlValidator.validate(s) as? RelayUrlResult.Invalid)?.error
    private fun ok(s: String) = (RelayUrlValidator.validate(s) as? RelayUrlResult.Valid)?.normalized

    @Test fun blankIsEmptyAndAllowed() {
        assertEquals(RelayUrlResult.Empty, RelayUrlValidator.validate("   "))
    }

    @Test fun wssPublicHostAccepted() {
        assertEquals("wss://relay.example.com", ok("  wss://relay.example.com  "))
        assertEquals("wss://relay.example.com:8443/path", ok("wss://relay.example.com:8443/path"))
        assertEquals("wss://Relay.Example.com", ok("WSS://Relay.Example.com"))
    }

    @Test fun wsOnlyForLocalAndPrivateHosts() {
        assertEquals("ws://192.168.1.23:8080", ok("ws://192.168.1.23:8080"))
        assertEquals("ws://10.0.2.2:8080", ok("ws://10.0.2.2:8080"))
        assertEquals("ws://172.16.0.5", ok("ws://172.16.0.5"))
        assertEquals("ws://localhost:8080", ok("ws://localhost:8080"))
        assertEquals("ws://[::1]:8080", ok("ws://[::1]:8080"))
        assertEquals("ws://pi.local:8080", ok("ws://pi.local:8080"))
        assertEquals(RelayUrlError.INSECURE_PUBLIC_HOST, err("ws://relay.example.com"))
        assertEquals(RelayUrlError.INSECURE_PUBLIC_HOST, err("ws://8.8.8.8:80"))
        assertEquals(RelayUrlError.INSECURE_PUBLIC_HOST, err("ws://172.32.0.1"))
    }

    @Test fun rejectsBadSchemes() {
        assertEquals(RelayUrlError.SCHEME, err("https://relay.example.com"))
        assertEquals(RelayUrlError.SCHEME, err("relay.example.com"))
        assertEquals(RelayUrlError.SCHEME, err("http://x.com"))
    }

    @Test fun rejectsMissingHost() {
        assertEquals(RelayUrlError.HOST_MISSING, err("wss://"))
        assertEquals(RelayUrlError.HOST_MISSING, err("wss://:8080"))
        assertEquals(RelayUrlError.HOST_MISSING, err("wss:///path"))
        assertEquals(RelayUrlError.HOST_MISSING, err("wss://bad_host.com"))
    }

    @Test fun rejectsSpacesAndControlChars() {
        assertEquals(RelayUrlError.WHITESPACE, err("wss://relay example.com"))
        assertEquals(RelayUrlError.WHITESPACE, err("wss://relay.example.com/a\tb"))
    }

    @Test fun rejectsCredentials() {
        assertEquals(RelayUrlError.CREDENTIALS, err("wss://user:pw@relay.example.com"))
        assertEquals(RelayUrlError.CREDENTIALS, err("wss://user@relay.example.com"))
    }

    @Test fun atSignInQueryIsNotCredentials() {
        assertEquals("wss://relay.example.com/?x=a@b", ok("wss://relay.example.com/?x=a@b"))
    }

    @Test fun rejectsBadPorts() {
        assertEquals(RelayUrlError.PORT, err("wss://relay.example.com:0"))
        assertEquals(RelayUrlError.PORT, err("wss://relay.example.com:70000"))
        assertEquals(RelayUrlError.PORT, err("wss://relay.example.com:abc"))
        assertEquals(RelayUrlError.PORT, err("wss://relay.example.com:"))
    }

    @Test fun privateHostHelper() {
        assertTrue(RelayUrlValidator.isLocalOrPrivateHost("127.0.0.1"))
        assertTrue(RelayUrlValidator.isLocalOrPrivateHost("169.254.1.1"))
        assertFalse(RelayUrlValidator.isLocalOrPrivateHost("192.169.1.1"))
        assertFalse(RelayUrlValidator.isLocalOrPrivateHost("300.1.1.1"))
        assertFalse(RelayUrlValidator.isLocalOrPrivateHost("example.com"))
    }
}
