package com.notearray.service;

import java.time.Duration;

/**
 * Rules for the failed-login backoff, kept free of framework and database
 * concerns so the delay/trial behaviour is unit-tested directly.
 *
 * <p>After each consecutive failure the next attempt for the same email is held
 * back for twice as long as the previous one (1s, 2s, 4s ... up to 64s). The
 * eighth consecutive failure locks the account out for 15 minutes and resets the
 * counter, so a caller gets at most eight permitted tries per lockout window. A
 * successful login clears the counter entirely.
 */
public final class LoginThrottlePolicy {

    /** Delay before the attempt that follows the first failure. */
    public static final Duration BASE_DELAY = Duration.ofSeconds(1);

    /** Longest single exponential delay (after the seventh consecutive failure). */
    public static final Duration MAX_DELAY = Duration.ofSeconds(64);

    /** Consecutive failures that exhaust the permitted tries and trigger a lockout. */
    public static final int MAX_CONSECUTIVE_FAILURES = 8;

    /** How long an account is locked once {@link #MAX_CONSECUTIVE_FAILURES} is reached. */
    public static final Duration LOCKOUT = Duration.ofMinutes(15);

    private LoginThrottlePolicy() {
    }

    /**
     * How long to hold back the next attempt after {@code consecutiveFailures}
     * failures have accumulated. {@link #MAX_CONSECUTIVE_FAILURES} or more means
     * the permitted-tries limit was hit, so the caller gets {@link #LOCKOUT}.
     */
    public static Duration delayAfterFailure(int consecutiveFailures) {
        if (consecutiveFailures <= 0) {
            return Duration.ZERO;
        }
        if (isLockout(consecutiveFailures)) {
            return LOCKOUT;
        }
        Duration delay = BASE_DELAY.multipliedBy(1L << (consecutiveFailures - 1));
        return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
    }

    /** True when the failure count reaches the permitted-tries limit. */
    public static boolean isLockout(int consecutiveFailures) {
        return consecutiveFailures >= MAX_CONSECUTIVE_FAILURES;
    }
}
