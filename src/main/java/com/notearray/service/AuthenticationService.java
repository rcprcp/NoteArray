package com.notearray.service;

import com.notearray.entity.User;
import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.UUID;

/**
 * Password verification for the login endpoint.
 *
 * <p>Kept separate from {@link com.notearray.resource.UserResource} so credential
 * checking can run in its own short transaction, after any throttling delay has
 * already been waited out without holding a database connection.
 */
@ApplicationScoped
public class AuthenticationService {

    /**
     * Hash compared when the email is unknown, so login timing does not reveal
     * which emails have accounts. Compared lazily on first use.
     */
    private static volatile String timingDecoyHash;

    /**
     * Return the user when the credentials match, else {@code null}. Exactly one
     * bcrypt comparison runs either way, so an unknown email costs the same as a
     * known one. Deliberately does <b>not</b> update {@code last_login} — when the
     * account has TOTP enabled the login is not complete until the second factor
     * is verified (see {@link #markLoginSuccess(UUID)}).
     */
    @Transactional
    public User verifyCredentials(String email, String password) {
        String normalizedEmail = EmailPolicy.normalizeEmail(email);
        User user = User.findByEmail(normalizedEmail);
        String storedHash = user != null ? user.passwordHash : timingDecoyHash();
        boolean matches = BcryptUtil.matches(password, storedHash);
        if (user == null || !matches) {
            return null;
        }
        return user;
    }

    /** Record a fully completed login (password + second factor). */
    @Transactional
    public void markLoginSuccess(UUID userId) {
        if (userId == null) {
            return;
        }
        User user = User.findById(userId);
        if (user != null) {
            user.lastLogin = Instant.now();
        }
    }

    private static String timingDecoyHash() {
        String hash = timingDecoyHash;
        if (hash == null) {
            // Costs one bcrypt computation on first login only.
            hash = BcryptUtil.bcryptHash(UUID.randomUUID().toString());
            timingDecoyHash = hash;
        }
        return hash;
    }
}
