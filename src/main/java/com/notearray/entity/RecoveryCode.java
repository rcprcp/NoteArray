package com.notearray.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Single-use recovery code for TOTP; only a SHA-256 hash is persisted. */
@Entity
@Table(name = "user_recovery_codes")
public class RecoveryCode extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    public UUID id;

    @Column(name = "user_id", nullable = false)
    public UUID userId;

    @Column(name = "code_hash", nullable = false, length = 64)
    public String codeHash;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "used_at")
    public Instant usedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public static List<RecoveryCode> findUnused(UUID userId) {
        return list("userId = ?1 AND usedAt IS NULL", userId);
    }

    public static long countUnused(UUID userId) {
        return count("userId = ?1 AND usedAt IS NULL", userId);
    }
}
