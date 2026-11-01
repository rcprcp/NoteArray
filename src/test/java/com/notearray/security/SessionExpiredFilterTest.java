package com.notearray.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The 401 body must distinguish an expired session from a failed login. */
class SessionExpiredFilterTest {

    @Test
    void decoratesBodyless401FromProtectedEndpoint() {
        assertTrue(SessionExpiredFilter.shouldDecorate(401, "notes", true));
        assertTrue(SessionExpiredFilter.shouldDecorate(401, "users/me", true));
        assertTrue(SessionExpiredFilter.shouldDecorate(401, "attachments/abc", true));
    }

    @Test
    void keepsLoginAndLogoutErrorsUnchanged() {
        assertFalse(SessionExpiredFilter.shouldDecorate(401, "users/login", true));
        assertFalse(SessionExpiredFilter.shouldDecorate(401, "users/login/totp", true));
        assertFalse(SessionExpiredFilter.shouldDecorate(401, "users", true));
        assertFalse(SessionExpiredFilter.shouldDecorate(401, "users/logout", true));
    }

    @Test
    void ignoresNon401AndResponsesThatAlreadyHaveABody() {
        assertFalse(SessionExpiredFilter.shouldDecorate(403, "notes", true));
        assertFalse(SessionExpiredFilter.shouldDecorate(500, "notes", true));
        assertFalse(SessionExpiredFilter.shouldDecorate(401, "notes", false));
    }
}
