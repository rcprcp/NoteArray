package com.notearray.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TotpCryptoTest {

    private final TotpCrypto crypto = new TotpCrypto();

    @Test
    void encryptsAndDecryptsWithA256BitKey() {
        crypto.encryptionKey = Optional.of(Base64.getEncoder().encodeToString(new byte[32]));

        String ciphertext = crypto.encrypt("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");

        assertTrue(ciphertext.startsWith("v1:"));
        assertFalse(ciphertext.contains("GEZDGNBV"));
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", crypto.decrypt(ciphertext));
    }

    @Test
    void tamperedCiphertextIsRejected() {
        crypto.encryptionKey = Optional.of(Base64.getEncoder().encodeToString(new byte[32]));
        String ciphertext = crypto.encrypt("secret");
        String tampered = ciphertext.substring(0, ciphertext.length() - 2)
                + (ciphertext.endsWith("AA") ? "BB" : "AA");

        assertThrows(IllegalStateException.class, () -> crypto.decrypt(tampered));
    }

    @Test
    void refusesToOperateWithoutAValidKey() {
        crypto.encryptionKey = Optional.empty();
        assertFalse(crypto.isConfigured());
        assertThrows(IllegalStateException.class, () -> crypto.encrypt("secret"));

        crypto.encryptionKey = Optional.of(Base64.getEncoder().encodeToString(new byte[16]));
        assertFalse(crypto.isConfigured());
        assertThrows(IllegalStateException.class, () -> crypto.encrypt("secret"));
    }
}
