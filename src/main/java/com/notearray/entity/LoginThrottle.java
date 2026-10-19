package com.notearray.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One row of throttling state, addressed by an opaque key so per-account failure
 * backoff and per-IP hourly windows share the same table (see
 * {@code V1.1.4__create_login_throttle.sql}).
 *
 * <p>Rows are created with defaults by {@link #ensureExists(String)} and then read
 * under a pessimistic lock ({@link #lock(String)}) so concurrent login attempts
 * cannot lose increments.
 */
@Entity
@Table(name = "login_throttle")
public class LoginThrottle extends PanacheEntityBase {

    @Id
    @Column(name = "throttle_key", length = 320, nullable = false)
    public String key;

    /** Consecutive failed logins for an email key. */
    @Column(name = "consecutive_failures", nullable = false)
    public int consecutiveFailures;

    /** Requests seen in the current hourly window for an IP key. */
    @Column(name = "window_count", nullable = false)
    public int windowCount;

    @Column(name = "window_started_at")
    public Instant windowStartedAt;

    /** When set, the key's next attempt must wait until this instant. */
    @Column(name = "next_allowed_at")
    public Instant nextAllowedAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    /**
     * Insert a defaults-only row when the key is unknown. Safe to call
     * concurrently: a losing insert is a no-op rather than an error.
     */
    public static void ensureExists(String key) {
        getEntityManager().createNativeQuery(
                        "INSERT INTO login_throttle (throttle_key) VALUES (:key) ON CONFLICT (throttle_key) DO NOTHING")
                .setParameter("key", key)
                .executeUpdate();
    }

    /** Load the row for {@code key} under a pessimistic write lock (never null after {@link #ensureExists}). */
    public static LoginThrottle lock(String key) {
        return getEntityManager().find(LoginThrottle.class, key, LockModeType.PESSIMISTIC_WRITE);
    }
}
