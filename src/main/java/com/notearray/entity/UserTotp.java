package com.notearray.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Optional TOTP enrollment for a user. {@code enabled == false} means the
 * enrollment was started (a pending secret exists) but no valid code has been
 * confirmed yet. The secret is stored encrypted (see TotpCrypto).
 */
@Entity
@Table(name = "user_totp")
public class UserTotp extends PanacheEntityBase {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    public UUID userId;

    @Column(name = "secret_ciphertext", nullable = false)
    public String secretCiphertext;

    @Column(name = "key_version", nullable = false)
    public short keyVersion = 1;

    @Column(nullable = false)
    public boolean enabled = false;

    /** Last successfully used RFC 6238 time step, to reject replayed codes. */
    @Column(name = "last_used_step")
    public Long lastUsedStep;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "enabled_at")
    public Instant enabledAt;

    public static UserTotp findByUserId(UUID userId) {
        return findById(userId);
    }
}
