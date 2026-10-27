package com.notearray.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TotpServiceTest {

    // RFC 6238 Appendix B seed: ASCII "12345678901234567890".
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void matchesTheRfc6238Vectors() {
        assertEquals("94287082", TotpService.codeAt(RFC_SECRET, 59 / 30, 8));
        assertEquals("07081804", TotpService.codeAt(RFC_SECRET, 1111111109 / 30, 8));
        assertEquals("14050471", TotpService.codeAt(RFC_SECRET, 1111111111 / 30, 8));
        assertEquals("89005924", TotpService.codeAt(RFC_SECRET, 1234567890 / 30, 8));
        assertEquals("69279037", TotpService.codeAt(RFC_SECRET, 2000000000 / 30, 8));
        assertEquals("65353130", TotpService.codeAt(RFC_SECRET, 20000000000L / 30, 8));
    }

    @Test
    void base32RoundTripsAndDecodesTheRfcSeed() {
        byte[] seed = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        String encoded = TotpService.base32Encode(seed);
        assertEquals(RFC_SECRET, encoded);
        assertEquals("12345678901234567890",
                new String(TotpService.base32Decode(encoded), StandardCharsets.US_ASCII));
    }

    @Test
    void acceptsOneStepOfClockDriftAndRejectsMalformedCodes() {
        TotpService service = new TotpService();
        service.clock = Clock.fixed(Instant.ofEpochSecond(1234567890L), ZoneOffset.UTC);
        long step = 1234567890L / 30;

        assertEquals(step, service.verify(RFC_SECRET, TotpService.codeAt(RFC_SECRET, step, 6)));
        assertEquals(step - 1, service.verify(RFC_SECRET, TotpService.codeAt(RFC_SECRET, step - 1, 6)));
        assertEquals(step + 1, service.verify(RFC_SECRET, TotpService.codeAt(RFC_SECRET, step + 1, 6)));
        assertEquals(-1, service.verify(RFC_SECRET, TotpService.codeAt(RFC_SECRET, step - 2, 6)));
        assertEquals(-1, service.verify(RFC_SECRET, "12345"));
        assertEquals(-1, service.verify(RFC_SECRET, "abcdef"));
        assertEquals(-1, service.verify(RFC_SECRET, null));
    }

    @Test
    void generatedSecretsAreBase32AndUnique() {
        TotpService service = new TotpService();
        String first = service.generateSecret();
        String second = service.generateSecret();
        assertTrue(first.matches("[A-Z2-7]{32}"));
        assertFalse(first.equals(second));
    }

    @Test
    void buildsAGoogleAuthenticatorCompatibleUri() {
        String url = TotpService.otpauthUrl("user@example.com", "NoteArray", RFC_SECRET);
        assertTrue(url.startsWith("otpauth://totp/NoteArray:user%40example.com?"));
        assertTrue(url.contains("secret=" + RFC_SECRET));
        assertTrue(url.contains("issuer=NoteArray"));
        assertTrue(url.contains("algorithm=SHA1"));
        assertTrue(url.contains("digits=6"));
        assertTrue(url.contains("period=30"));
    }

    @Test
    void normalizeCodeIsStrict() {
        assertEquals("012345", TotpService.normalizeCode(" 012345 "));
        assertNull(TotpService.normalizeCode("0123456"));
        assertNull(TotpService.normalizeCode("01234a"));
        assertNull(TotpService.normalizeCode(""));
    }
}
