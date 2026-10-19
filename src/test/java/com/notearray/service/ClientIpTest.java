package com.notearray.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** The header-parsing half of {@link ClientIp}; the socket half needs a live request. */
class ClientIpTest {

    @Test
    void takesTheRightmostEntryAddedByTheTrustedProxy() {
        assertEquals("203.0.113.9", ClientIp.rightmostForwarded("198.51.100.4, 203.0.113.9"));
    }

    @Test
    void skipsBlankTrailingEntries() {
        assertEquals("203.0.113.9", ClientIp.rightmostForwarded("198.51.100.4, 203.0.113.9, "));
    }

    @Test
    void handlesASingleValue() {
        assertEquals("203.0.113.9", ClientIp.rightmostForwarded("203.0.113.9"));
    }

    @Test
    void treatsAbsentOrBlankHeadersAsMissing() {
        assertNull(ClientIp.rightmostForwarded(null));
        assertNull(ClientIp.rightmostForwarded(""));
        assertNull(ClientIp.rightmostForwarded("  "));
        assertNull(ClientIp.rightmostForwarded(" , , "));
    }

    @Test
    void ignoresTheHeaderWhenTheProxyIsNotTrusted() {
        // No request objects available, so the fallback is exercised: with
        // trustProxy=false the header must never be consulted.
        assertEquals("unknown", ClientIp.resolve(null, null, false));
    }
}
