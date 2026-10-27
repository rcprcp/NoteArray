package com.notearray.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * AES-256-GCM encryption for TOTP secrets at rest.
 *
 * <p>Format: {@code v1:<base64 iv>:<base64 ciphertext+tag>}. The 32-byte key
 * comes from {@code notearray.totp.encryption-key} (base64). Without it,
 * enrollment is refused (503) and existing secrets cannot be decrypted — the
 * recovery-code path still works so users are not permanently locked out while
 * an operator restores the key.
 */
@ApplicationScoped
public class TotpCrypto {

    private static final String VERSION_PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    @ConfigProperty(name = "notearray.totp.encryption-key")
    Optional<String> encryptionKey;

    public boolean isConfigured() {
        try {
            return key() != null;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public String encrypt(String plaintext) {
        byte[] key = requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return VERSION_PREFIX
                    + Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to encrypt the TOTP secret", e);
        }
    }

    public String decrypt(String stored) {
        byte[] key = requireKey();
        if (stored == null || !stored.startsWith(VERSION_PREFIX)) {
            throw new IllegalStateException("Unsupported TOTP secret format");
        }
        String[] parts = stored.substring(VERSION_PREFIX.length()).split(":", -1);
        if (parts.length != 2) {
            throw new IllegalStateException("Malformed TOTP secret");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IllegalStateException("Unable to decrypt the TOTP secret", e);
        }
    }

    private byte[] requireKey() {
        byte[] key = key();
        if (key == null) {
            throw new IllegalStateException("notearray.totp.encryption-key is not configured");
        }
        return key;
    }

    private byte[] key() {
        String encoded = encryptionKey != null ? encryptionKey.orElse(null) : null;
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("notearray.totp.encryption-key is not valid base64", e);
        }
        if (key.length != KEY_BYTES) {
            throw new IllegalStateException("notearray.totp.encryption-key must be 32 bytes (base64)");
        }
        return key;
    }
}
