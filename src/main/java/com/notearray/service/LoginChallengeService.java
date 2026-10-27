package com.notearray.service;

import com.notearray.entity.LoginChallenge;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Short-lived, single-use challenges created after a correct password when the
 * account requires a TOTP second factor. Only the token hash is stored, so a
 * database leak cannot be replayed. One active challenge per user.
 */
@ApplicationScoped
public class LoginChallengeService {

    public static final Duration TTL = Duration.ofMinutes(5);
    public static final int MAX_ATTEMPTS = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Create a challenge, replacing any previous one for this user. */
    @Transactional
    public IssuedChallenge create(UUID userId) {
        LoginChallenge.delete("userId", userId);
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        LoginChallenge challenge = new LoginChallenge();
        challenge.userId = userId;
        challenge.tokenHash = hash(token);
        challenge.expiresAt = Instant.now().plus(TTL);
        challenge.persist();
        return new IssuedChallenge(token, TTL.toSeconds());
    }

    /** Look up a live challenge by its opaque token, or null when unknown/expired. */
    @Transactional
    public LoginChallenge findLive(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        LoginChallenge challenge = LoginChallenge.findByTokenHash(hash(token.trim()));
        if (challenge == null) {
            return null;
        }
        if (challenge.expiresAt.isBefore(Instant.now())) {
            challenge.delete();
            return null;
        }
        return challenge;
    }

    /**
     * Count a failed verification. Returns false when the attempt budget is
     * exhausted (the challenge is deleted, so the user must re-enter the password).
     */
    @Transactional
    public boolean recordFailure(UUID challengeId) {
        LoginChallenge challenge = challengeId == null ? null : LoginChallenge.findById(challengeId);
        if (challenge == null) {
            return false;
        }
        challenge.attempts = (short) (challenge.attempts + 1);
        if (challenge.attempts >= MAX_ATTEMPTS) {
            challenge.delete();
            return false;
        }
        return true;
    }

    @Transactional
    public void consume(UUID challengeId) {
        LoginChallenge challenge = challengeId == null ? null : LoginChallenge.findById(challengeId);
        if (challenge != null) {
            challenge.delete();
        }
    }

    @Transactional
    public void deleteForUser(UUID userId) {
        LoginChallenge.delete("userId", userId);
    }

    @Transactional
    @Scheduled(every = "1h")
    void purgeExpired() {
        LoginChallenge.delete("expiresAt < ?1", Instant.now());
    }

    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
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

    public record IssuedChallenge(String token, long expiresInSeconds) {
    }
}
