package com.notearray.service;

import com.notearray.entity.Session;
import com.notearray.entity.User;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Server-side login sessions, addressed by an HttpOnly, SameSite=Strict cookie.
 *
 * <p>Sessions are persisted in the {@code sessions} table through Hibernate/Panache, so they
 * survive application restarts and are shared across multiple application instances that use
 * the same database. A user may be signed in on many devices at once; each login is its own row.
 * Only a SHA-256 hash of the token is stored, never the raw token.
 *
 * <p><b>CSRF Invariant:</b> Cookie-based session authentication is protected against CSRF by
 * three layers that must be maintained together:
 * <ol>
 *   <li>{@code SameSite=Strict} on {@link #COOKIE_NAME} (never relax to {@code None} without a
 *       per-request CSRF token).</li>
 *   <li>All state-changing endpoints require {@code application/json} or
 *       {@code application/octet-stream} bodies (never form-encoded bodies).</li>
 *   <li>{@link com.notearray.security.CsrfFilter} enforces {@code Sec-Fetch-Site} and
 *       {@code Origin} checks on all non-GET/HEAD/OPTIONS requests.</li>
 * </ol>
 */
@ApplicationScoped
public class SessionService {

    /** Name of the cookie carrying the session token. */
    public static final String COOKIE_NAME = "NOTEARRAY_SESSION";

    private static final Duration SESSION_TTL = Duration.ofDays(7);

    /** Add the Secure attribute to session cookies; enable behind TLS (defaults on in prod). */
    @ConfigProperty(name = "notearray.session.cookie-secure", defaultValue = "false")
    boolean cookieSecure;

    /**
     * Create a new session for the user and return the raw token to put in the cookie.
     * Existing sessions for the user are left intact, so other devices stay signed in.
     * The caller must run inside a transaction so the row is flushed.
     */
    @Transactional
    public String create(User user) {
        String token = UUID.randomUUID().toString();
        Instant now = Instant.now();

        Session session = new Session();
        session.tokenHash = hash(token);
        session.userId = user.id;
        session.createdAt = now;
        session.expiresAt = now.plus(SESSION_TTL);
        session.persist();

        return token;
    }

    /**
     * Return the user bound to the cookie, or {@code null} when the cookie is missing, unknown
     * or expired. An expired session is deleted as a side effect.
     */
    @Transactional
    public User resolve(Cookie cookie) {
        String token = token(cookie);
        if (token == null) {
            return null;
        }
        Session session = Session.findByTokenHash(hash(token));
        if (session == null) {
            return null;
        }
        if (session.expiresAt.isBefore(Instant.now())) {
            session.delete();
            return null;
        }
        return User.findById(session.userId);
    }

    /** Drop the session bound to the cookie, if any (signs out one device only). */
    @Transactional
    public void invalidate(Cookie cookie) {
        String token = token(cookie);
        if (token == null) {
            return;
        }
        Session session = Session.findByTokenHash(hash(token));
        if (session != null) {
            session.delete();
        }
    }

    /** Drop every session for a user (e.g. after a password change). */
    @Transactional
    public void invalidateAllForUser(UUID userId) {
        Session.deleteByUserId(userId);
    }

    /** Periodically remove expired sessions so the table does not accumulate dead rows. */
    @Transactional
    @Scheduled(every = "1h")
    void purgeExpiredSessions() {
        Session.deleteExpired();
    }

    /** Set-Cookie header that hands the token to the browser. */
    public String cookieHeader(String token) {
        return COOKIE_NAME + "=" + token
                + "; Path=/"
                + "; Max-Age=" + SESSION_TTL.toSeconds()
                + "; HttpOnly; SameSite=Strict"
                + secureAttribute();
    }

    /** Set-Cookie header that removes the cookie from the browser. */
    public String expiredCookieHeader() {
        return COOKIE_NAME + "="
                + "; Path=/"
                + "; Max-Age=0"
                + "; HttpOnly; SameSite=Strict"
                + secureAttribute();
    }

    private String secureAttribute() {
        return cookieSecure ? "; Secure" : "";
    }

    private static String token(Cookie cookie) {
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            return null;
        }
        return cookie.getValue();
    }

    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
