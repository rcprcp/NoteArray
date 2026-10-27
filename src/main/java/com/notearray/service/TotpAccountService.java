package com.notearray.service;

import com.notearray.entity.UserTotp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Enrollment and verification state for TOTP, on top of {@link TotpService}
 * (algorithm), {@link TotpCrypto} (secret at rest) and
 * {@link RecoveryCodeService} (break-glass codes).
 */
@ApplicationScoped
public class TotpAccountService {

    public enum Verification {
        VALID, INVALID, UNAVAILABLE
    }

    @Inject
    TotpService totp;

    @Inject
    TotpCrypto crypto;

    @Inject
    RecoveryCodeService recoveryCodes;

    public boolean isEnabled(UUID userId) {
        UserTotp row = UserTotp.findByUserId(userId);
        return row != null && row.enabled;
    }

    public long remainingRecoveryCodes(UUID userId) {
        return recoveryCodes.remaining(userId);
    }

    public Instant enabledAt(UUID userId) {
        UserTotp row = UserTotp.findByUserId(userId);
        return row != null && row.enabled ? row.enabledAt : null;
    }

    /** Save (or rotate) a pending enrollment secret. Refused once enabled. */
    @Transactional
    public String startEnrollment(UUID userId) {
        UserTotp existing = UserTotp.findByUserId(userId);
        if (existing != null && existing.enabled) {
            throw new IllegalStateException("TOTP is already enabled");
        }
        String secret = totp.generateSecret();
        String ciphertext = crypto.encrypt(secret);
        boolean created = existing == null;
        if (created) {
            existing = new UserTotp();
            existing.userId = userId;
            existing.createdAt = Instant.now();
        }
        existing.secretCiphertext = ciphertext;
        existing.keyVersion = 1;
        existing.enabled = false;
        existing.enabledAt = null;
        existing.lastUsedStep = null;
        if (created) {
            existing.persist();
        }
        return secret;
    }

    /** Confirm a pending enrollment and return the new recovery codes (shown once). */
    @Transactional
    public List<String> enable(UUID userId, String code) {
        UserTotp row = UserTotp.findByUserId(userId);
        if (row == null || row.enabled) {
            return null;
        }
        String secret;
        try {
            secret = crypto.decrypt(row.secretCiphertext);
        } catch (IllegalStateException e) {
            throw e; // caller maps this to 503
        }
        long step = totp.verify(secret, code);
        if (step < 0) {
            return null;
        }
        row.enabled = true;
        row.enabledAt = Instant.now();
        return recoveryCodes.replaceCodes(userId);
    }

    /**
     * Verify a TOTP code or a recovery code for login or a sensitive action.
     * A successful TOTP step is consumed (replay protection) under a row lock;
     * a recovery code is marked used.
     */
    @Transactional
    public Verification verify(UUID userId, String code, String recoveryCode) {
        if (recoveryCode != null && !recoveryCode.isBlank()) {
            return recoveryCodes.consume(userId, recoveryCode) ? Verification.VALID : Verification.INVALID;
        }
        UserTotp row = UserTotp.findByUserId(userId);
        if (row == null || !row.enabled) {
            return Verification.INVALID;
        }
        // Serialize concurrent attempts so the same step cannot be used twice.
        UserTotp.getEntityManager().lock(row, LockModeType.PESSIMISTIC_WRITE);
        String secret;
        try {
            secret = crypto.decrypt(row.secretCiphertext);
        } catch (IllegalStateException e) {
            return Verification.UNAVAILABLE;
        }
        long step = totp.verify(secret, code);
        if (step < 0 || (row.lastUsedStep != null && step <= row.lastUsedStep)) {
            return Verification.INVALID;
        }
        row.lastUsedStep = step;
        return Verification.VALID;
    }

    /** Remove the factor and all recovery codes. */
    @Transactional
    public void disable(UUID userId) {
        UserTotp row = UserTotp.findByUserId(userId);
        if (row != null) {
            row.delete();
        }
        recoveryCodes.deleteAll(userId);
    }

    /** Drop enrollments that were started but never confirmed (e.g. abandoned QR setup). */
    @Transactional
    @io.quarkus.scheduler.Scheduled(every = "24h")
    void purgeAbandonedEnrollments() {
        UserTotp.delete("enabled = false AND createdAt < ?1", Instant.now().minus(Duration.ofDays(1)));
    }
}
