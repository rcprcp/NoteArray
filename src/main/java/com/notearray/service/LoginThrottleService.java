package com.notearray.service;

import com.notearray.entity.LoginThrottle;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Throttles the login and signup endpoints.
 *
 * <p>Two independent mechanisms share the {@code login_throttle} table:
 * <ul>
 *   <li><b>Per-account backoff</b> — after a failed login the next attempt for the
 *       submitted email is held back 1s, 2s, 4s ... (see {@link LoginThrottlePolicy}),
 *       then locked for 15 minutes once the permitted tries are exhausted. The email
 *       is keyed even when no such account exists, so throttling never reveals which
 *       addresses are registered.</li>
 *   <li><b>Per-IP hourly budget</b> — a hard cap on requests to the login and signup
 *       endpoints, which also limits spraying many emails from one source.</li>
 * </ul>
 *
 * <p>State lives in the database (not in memory) so it survives restarts and is
 * shared by every application instance.
 */
@ApplicationScoped
public class LoginThrottleService {

    /** Longest a request is held server-side while it waits out a backoff window. */
    public static final Duration MAX_SERVER_SIDE_WAIT = Duration.ofSeconds(5);

    private static final String EMAIL_PREFIX = "email:";
    private static final String LOGIN_IP_PREFIX = "login-ip:";
    private static final String SIGNUP_IP_PREFIX = "signup-ip:";
    private static final String TOTP_IP_PREFIX = "totp-ip:";
    private static final String TOTP_USER_PREFIX = "totp-user:";
    private static final Duration WINDOW = Duration.ofHours(1);
    private static final Duration STALE_AFTER = Duration.ofHours(2);

    /** Hourly request budget per client IP for the login endpoint. */
    @ConfigProperty(name = "notearray.rate-limit.login-per-hour-per-ip", defaultValue = "120")
    int loginPerHourPerIp;

    /** Hourly request budget per client IP for the signup endpoint. */
    @ConfigProperty(name = "notearray.rate-limit.signup-per-hour-per-ip", defaultValue = "30")
    int signupPerHourPerIp;

    /** Hourly request budget per client IP for the TOTP verification endpoint. */
    @ConfigProperty(name = "notearray.rate-limit.totp-per-hour-per-ip", defaultValue = "120")
    int totpPerHourPerIp;

    /**
     * Outcome of reserving a request slot. Either the request may proceed
     * ({@code allowed}, optionally after {@code waitSeconds} of server-side delay)
     * or it is rejected and the client is told to retry after
     * {@code retryAfterSeconds}.
     */
    public record Decision(boolean allowed, long retryAfterSeconds, long waitSeconds) {

        static Decision allow() {
            return new Decision(true, 0, 0);
        }

        static Decision allowAfter(long seconds) {
            return new Decision(true, 0, seconds);
        }

        static Decision reject(long seconds) {
            return new Decision(false, seconds, 0);
        }
    }

    /**
     * Charge one request against the login IP budget and report whether the
     * email's backoff window is still open. Called before the credentials are
     * checked, for every request, so the budget cannot be sidestepped by mixing
     * successful and failed logins.
     */
    @Transactional
    public Decision startLogin(String email, String ip) {
        long windowRetry = chargeWindow(LOGIN_IP_PREFIX + ip, loginPerHourPerIp);
        if (windowRetry > 0) {
            return Decision.reject(windowRetry);
        }
        long wait = remainingBackoffSeconds(emailKey(email));
        if (wait <= 0) {
            return Decision.allow();
        }
        return wait <= MAX_SERVER_SIDE_WAIT.toSeconds() ? Decision.allowAfter(wait) : Decision.reject(wait);
    }

    /** Charge one request against the signup IP budget. */
    @Transactional
    public Decision startSignup(String ip) {
        long windowRetry = chargeWindow(SIGNUP_IP_PREFIX + ip, signupPerHourPerIp);
        return windowRetry > 0 ? Decision.reject(windowRetry) : Decision.allow();
    }

    /**
     * Record a failed login: extend the email's backoff, or start a lockout and
     * clear the counter once the permitted tries are exhausted.
     */
    @Transactional
    public void recordLoginFailure(String email) {
        LoginThrottle row = lockOrCreate(emailKey(email));
        int failures = row.consecutiveFailures + 1;
        Instant now = Instant.now();
        row.nextAllowedAt = now.plus(LoginThrottlePolicy.delayAfterFailure(failures));
        row.consecutiveFailures = LoginThrottlePolicy.isLockout(failures) ? 0 : failures;
        row.updatedAt = now;
    }

    /** Clear an email's backoff after a successful login. */
    @Transactional
    public void recordLoginSuccess(String email) {
        LoginThrottle.deleteById(emailKey(email));
    }

    /**
     * Budget a TOTP verification attempt: per-IP hourly window + the user's
     * second-factor backoff. Kept separate from the password backoff so guessing
     * codes cannot lock the password itself, and vice versa.
     */
    @Transactional
    public Decision startTotpLogin(UUID userId, String ip) {
        long windowRetry = chargeWindow(TOTP_IP_PREFIX + ip, totpPerHourPerIp);
        if (windowRetry > 0) {
            return Decision.reject(windowRetry);
        }
        long wait = remainingBackoffSeconds(totpUserKey(userId));
        if (wait <= 0) {
            return Decision.allow();
        }
        return wait <= MAX_SERVER_SIDE_WAIT.toSeconds() ? Decision.allowAfter(wait) : Decision.reject(wait);
    }

    /** Extend the second-factor backoff after a wrong TOTP/recovery code. */
    @Transactional
    public void recordTotpFailure(UUID userId) {
        LoginThrottle row = lockOrCreate(totpUserKey(userId));
        int failures = row.consecutiveFailures + 1;
        Instant now = Instant.now();
        row.nextAllowedAt = now.plus(LoginThrottlePolicy.delayAfterFailure(failures));
        row.consecutiveFailures = LoginThrottlePolicy.isLockout(failures) ? 0 : failures;
        row.updatedAt = now;
    }

    /** Clear the second-factor backoff after a successful verification. */
    @Transactional
    public void recordTotpSuccess(UUID userId) {
        LoginThrottle.deleteById(totpUserKey(userId));
    }

    /** Drop rows that can no longer affect a decision (windows are 1h, lockouts 15min). */
    @Transactional
    @Scheduled(every = "1h")
    void purgeStale() {
        LoginThrottle.delete("updatedAt < ?1", Instant.now().minus(STALE_AFTER));
    }

    /** Count one request against a fixed hourly window; seconds until reset if over budget, else 0. */
    private long chargeWindow(String key, int limit) {
        LoginThrottle row = lockOrCreate(key);
        Instant now = Instant.now();
        if (row.windowStartedAt == null || !row.windowStartedAt.plus(WINDOW).isAfter(now)) {
            row.windowStartedAt = now;
            row.windowCount = 0;
        }
        row.windowCount++;
        row.updatedAt = now;
        return row.windowCount > limit ? secondsUntil(row.windowStartedAt.plus(WINDOW), now) : 0;
    }

    /** Seconds until the email may attempt a login again (0 when no backoff is active). */
    private long remainingBackoffSeconds(String key) {
        LoginThrottle row = LoginThrottle.findById(key);
        return row == null || row.nextAllowedAt == null ? 0 : secondsUntil(row.nextAllowedAt, Instant.now());
    }

    private static LoginThrottle lockOrCreate(String key) {
        LoginThrottle.ensureExists(key);
        return LoginThrottle.lock(key);
    }

    private static String emailKey(String email) {
        return EMAIL_PREFIX + email.trim().toLowerCase(Locale.ROOT);
    }

    private static String totpUserKey(UUID userId) {
        return TOTP_USER_PREFIX + userId;
    }

    /** Whole seconds from {@code now} to {@code instant}, never less than one. */
    private static long secondsUntil(Instant instant, Instant now) {
        long millis = Duration.between(now, instant).toMillis();
        return millis <= 0 ? 0 : (millis + 999) / 1000;
    }
}
