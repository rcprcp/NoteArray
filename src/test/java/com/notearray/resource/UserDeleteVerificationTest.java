package com.notearray.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.notearray.entity.User;
import com.notearray.resource.UserResource.DeleteRequest;
import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Password re-verification for account deletion: the session cookie alone
 * must not be able to irreversibly delete an account (REVIEW finding #27).
 */
class UserDeleteVerificationTest {

    private static final String CURRENT_PASSWORD = "OldPassword1";
    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.passwordHash = BcryptUtil.bcryptHash(CURRENT_PASSWORD);
    }

    @Test
    void requiresPasswordWhenBodyIsMissing() {
        // DELETE without a JSON body → the password is definitely not supplied.
        Response res = UserResource.validateDeleteConfirmation(user, null);
        assertNotNull(res);
        assertEquals(400, res.getStatus());
        assertEquals("Password is required to delete your account", res.getEntity());
    }

    @Test
    void requiresPasswordWhenFieldIsMissing() {
        Response res = UserResource.validateDeleteConfirmation(user, new DeleteRequest(null));
        assertNotNull(res);
        assertEquals(400, res.getStatus());
        assertEquals("Password is required to delete your account", res.getEntity());
    }

    @Test
    void rejectsBlankPassword() {
        Response res = UserResource.validateDeleteConfirmation(user, new DeleteRequest("   "));
        assertNotNull(res);
        assertEquals(400, res.getStatus());
        assertEquals("Password is required to delete your account", res.getEntity());
    }

    @Test
    void rejectsWrongPassword() {
        Response res = UserResource.validateDeleteConfirmation(user, new DeleteRequest("WrongCurrent1"));
        assertNotNull(res);
        assertEquals(400, res.getStatus());
        assertEquals("Current password is incorrect", res.getEntity());
    }

    @Test
    void acceptsCorrectPassword() {
        assertNull(UserResource.validateDeleteConfirmation(user, new DeleteRequest(CURRENT_PASSWORD)));
    }
}
