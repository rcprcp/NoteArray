package com.notearray.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class LoginThrottlePolicyTest {

    @Test
    void delaysDoubleAfterEachFailure() {
        assertEquals(Duration.ofSeconds(1), LoginThrottlePolicy.delayAfterFailure(1));
        assertEquals(Duration.ofSeconds(2), LoginThrottlePolicy.delayAfterFailure(2));
        assertEquals(Duration.ofSeconds(4), LoginThrottlePolicy.delayAfterFailure(3));
        assertEquals(Duration.ofSeconds(8), LoginThrottlePolicy.delayAfterFailure(4));
        assertEquals(Duration.ofSeconds(16), LoginThrottlePolicy.delayAfterFailure(5));
        assertEquals(Duration.ofSeconds(32), LoginThrottlePolicy.delayAfterFailure(6));
        assertEquals(Duration.ofSeconds(64), LoginThrottlePolicy.delayAfterFailure(7));
    }

    @Test
    void reachesThePermittedTriesLimitAtEightFailures() {
        assertEquals(LoginThrottlePolicy.LOCKOUT, LoginThrottlePolicy.delayAfterFailure(8));
        assertEquals(LoginThrottlePolicy.LOCKOUT, LoginThrottlePolicy.delayAfterFailure(9));
        assertEquals(LoginThrottlePolicy.LOCKOUT, LoginThrottlePolicy.delayAfterFailure(99));
    }

    @Test
    void delayIsPositiveAndNeverExceedsTheCap() {
        for (int failures = 1; failures <= LoginThrottlePolicy.MAX_CONSECUTIVE_FAILURES; failures++) {
            Duration delay = LoginThrottlePolicy.delayAfterFailure(failures);
            assertFalse(delay.isNegative(), "delay must not be negative at " + failures);
            assertTrue(delay.compareTo(LoginThrottlePolicy.LOCKOUT) <= 0,
                    "delay must not exceed the lockout at " + failures);
        }
    }

    @Test
    void noFailuresMeansNoDelay() {
        assertEquals(Duration.ZERO, LoginThrottlePolicy.delayAfterFailure(0));
        assertEquals(Duration.ZERO, LoginThrottlePolicy.delayAfterFailure(-1));
    }

    @Test
    void lockoutBoundaryIsInclusive() {
        assertFalse(LoginThrottlePolicy.isLockout(7));
        assertTrue(LoginThrottlePolicy.isLockout(8));
    }
}
