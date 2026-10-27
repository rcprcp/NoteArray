package com.notearray.service;

import com.notearray.entity.RecoveryCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Generates and verifies single-use recovery codes.
 *
 * <p>Ten codes, 12 unambiguous characters each (~60 bits), displayed once as
 * {@code XXXX-XXXX-XXXX}. Only a SHA-256 hash is stored. High entropy makes a
 * salt/pepper unnecessary, and verification remains possible when the TOTP
 * encryption key is unavailable.
 */
@ApplicationScoped
public class RecoveryCodeService {

    public static final int CODE_COUNT = 10;
    private static final int CODE_LENGTH = 12;
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Create a fresh set of plaintext codes, replacing any stored ones. */
    @Transactional
    public List<String> replaceCodes(UUID userId) {
        RecoveryCode.delete("userId", userId);
        List<String> plaintext = new ArrayList<>(CODE_COUNT);
        for (int i = 0; i < CODE_COUNT; i++) {
            String code = generateCode();
            RecoveryCode row = new RecoveryCode();
            row.userId = userId;
            row.codeHash = hash(code);
            row.persist();
            plaintext.add(format(code));
        }
        return plaintext;
    }

    /** Consume one matching unused code; true when it was valid and marked used. */
    @Transactional
    public boolean consume(UUID userId, String candidate) {
        String normalized = normalize(candidate);
        if (normalized == null) {
            return false;
        }
        String candidateHash = hash(normalized);
        for (RecoveryCode row : RecoveryCode.findUnused(userId)) {
            if (MessageDigest.isEqual(
                    row.codeHash.getBytes(StandardCharsets.US_ASCII),
                    candidateHash.getBytes(StandardCharsets.US_ASCII))) {
                row.usedAt = Instant.now();
                return true;
            }
        }
        return false;
    }

    public long remaining(UUID userId) {
        return RecoveryCode.countUnused(userId);
    }

    @Transactional
    public void deleteAll(UUID userId) {
        RecoveryCode.delete("userId", userId);
    }

    /** Generate 12 characters without ambiguous 0/1/I/O. */
    public static String generateCode() {
        StringBuilder out = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            out.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return out.toString();
    }

    public static String format(String code) {
        return code.substring(0, 4) + "-" + code.substring(4, 8) + "-" + code.substring(8, 12);
    }

    /** Uppercase and strip dashes/spaces; null unless exactly 12 valid characters. */
    public static String normalize(String candidate) {
        if (candidate == null) {
            return null;
        }
        String normalized = candidate.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (normalized.length() != CODE_LENGTH) {
            return null;
        }
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (new String(ALPHABET).indexOf(c) < 0) {
                return null;
            }
        }
        return normalized;
    }

    public static String hash(String normalizedCode) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalizedCode.getBytes(StandardCharsets.US_ASCII));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
