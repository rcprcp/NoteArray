package com.notearray.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.notearray.service.EmailPolicy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User extends BaseEntity {

    public static final String DEFAULT_THEME_COLOR = "blue";

    @Column(name = "name", nullable = false, unique = true, length = 255)
    public String name;

    @Column(name = "email", nullable = false, unique = true, length = 320)
    public String email;

    @JsonIgnore
    @Column(name = "password_hash", nullable = false)
    public String passwordHash;

    /** Mantine primary color name chosen by the user in the UI. */
    @Column(name = "theme_color", nullable = false, length = 32)
    public String themeColor = DEFAULT_THEME_COLOR;

    /** Timestamp of the user's last successful login. */
    @Column(name = "last_login")
    public Instant lastLogin;

    /**
     * Superuser flag. Read-only for the application: it is never inserted or updated
     * by code. Managers set it directly in the database (psql). Not exposed to the UI.
     */
    @JsonIgnore
    @Column(name = "superuser", nullable = false, insertable = false, updatable = false)
    public boolean superuser;

    public static User findByEmail(String email) {
        String normalized = EmailPolicy.normalizeEmail(email);
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        return find("LOWER(email) = ?1", normalized).firstResult();
    }

    public static User findByName(String name) {
        String normalized = EmailPolicy.normalizeName(name);
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        return find("name", normalized).firstResult();
    }
}
