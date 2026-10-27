package com.notearray.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 TOTP primitives (SHA-1, 6 digits, 30-second period by default) with
 * the Google Authenticator conventions: Base32 secrets, ±1 step tolerance and
 * constant-time code comparison. No framework/database concerns here, so the
 * algorithm is unit-testable against the RFC vectors.
 */
@ApplicationScoped
public class TotpService {

    public static final int DIGITS = 6;
    public static final int PERIOD_SECONDS = 30;
    public static final int ALLOWED_STEP_SKEW = 1;
    public static final int SECRET_BYTES = 20;

    private static final char[] BASE32_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Overridable for deterministic tests; production uses the system clock. */
    Clock clock = Clock.systemUTC();

    /** Generate a 160-bit secret suitable for Google Authenticator. */
    public String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    public long currentStep() {
        return clock.instant().getEpochSecond() / PERIOD_SECONDS;
    }

    /**
     * Verify a six-digit code against the current step and its neighbours.
     *
     * @return the matched time step, or {@code -1} when the code is malformed or
     *         does not match. Callers must reject a matched step that is not
     *         strictly greater than the last used step (replay protection).
     */
    public long verify(String secretBase32, String code) {
        String normalized = normalizeCode(code);
        if (normalized == null) {
            return -1;
        }
        long step = currentStep();
        for (long candidate = step - ALLOWED_STEP_SKEW; candidate <= step + ALLOWED_STEP_SKEW; candidate++) {
            String expected = codeAt(secretBase32, candidate, DIGITS);
            if (expected != null && MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    normalized.getBytes(StandardCharsets.US_ASCII))) {
                return candidate;
            }
        }
        return -1;
    }

    /** Strict six-digit form; returns null for anything else. */
    public static String normalizeCode(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        if (trimmed.length() != DIGITS || !trimmed.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return trimmed;
    }

    /** otpauth:// URI understood by Google Authenticator and compatible apps. */
    public static String otpauthUrl(String accountName, String issuer, String secretBase32) {
        String account = accountName == null || accountName.isBlank() ? "account" : accountName.trim();
        String label = urlEncode(issuer) + ":" + urlEncode(account);
        return "otpauth://totp/" + label
                + "?secret=" + urlEncode(secretBase32)
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1"
                + "&digits=" + DIGITS
                + "&period=" + PERIOD_SECONDS;
    }

    static String codeAt(String secretBase32, long step, int digits) {
        byte[] key;
        try {
            key = base32Decode(secretBase32);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (key.length == 0) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int modulus = (int) Math.pow(10, digits);
            return String.format(Locale.ROOT, "%0" + digits + "d", binary % modulus);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 is unavailable", e);
        }
    }

    public static String base32Encode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                out.append(BASE32_ALPHABET[(buffer >> (bitsLeft - 5)) & 0x1f]);
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            out.append(BASE32_ALPHABET[(buffer << (5 - bitsLeft)) & 0x1f]);
        }
        return out.toString();
    }

    public static byte[] base32Decode(String input) {
        if (input == null) {
            throw new IllegalArgumentException("secret is required");
        }
        String normalized = input.replace(" ", "").replace("=", "").toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("secret is empty");
        }
        int buffer = 0;
        int bitsLeft = 0;
        byte[] out = new byte[normalized.length() * 5 / 8];
        int index = 0;
        for (int i = 0; i < normalized.length(); i++) {
            int value = decodeChar(normalized.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("invalid Base32 character");
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[index++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return out;
    }

    private static int decodeChar(char c) {
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        }
        if (c >= '2' && c <= '7') {
            return c - '2' + 26;
        }
        return -1;
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
