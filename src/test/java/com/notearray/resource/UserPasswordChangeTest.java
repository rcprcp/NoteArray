package com.notearray.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.notearray.entity.User;
import com.notearray.resource.UserResource.UserRequest;
import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserPasswordChangeTest {

    private static final String CURRENT_PASSWORD = "OldPassword1";
    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.passwordHash = BcryptUtil.bcryptHash(CURRENT_PASSWORD);
    }

    @Test
    void requiresCurrentPassword() {
        UserRequest req = new UserRequest(null, null, "NewPassword1!", "", "NewPassword1!", null);
        Response res = UserResource.validatePasswordChange(user, req);
        assertNotNull(res);
        assertEquals(400, res.getStatus());
        assertEquals("Current password is required", res.getEntity());
    }

    @Test
    void requiresNewPassword() {
        UserRequest req = new UserRequest(null, null, "", CURRENT_PASSWORD, "", null);
        Response res = UserResource.validatePasswordChange(user, req);
        assertNotNull(res);
        assertEquals(400, res.getStatus());
        assertEquals("New password is required", res.getEntity());
    }

    @Test
    void requiresPasswordVerificationWhenProvidedAsBlank() {
        UserRequest req = new UserRequest(null, null, "NewPassword1!", CURRENT_PASSWORD, "   ", null);
        Response res = UserResource.validatePasswordChange(user, req);
        assertNotNull(res);
        assertEquals(400, res.getStatus());
        assertEquals("Password verification is required", res.getEntity());
    }

    @Test
    void checksCurrentPasswordBeforeValidatingNewPassword() {
        // Both current password and new password are invalid; current password must be reported first.
        UserRequest req = new UserRequest(null, null, "short", "WrongCurrent1", "mismatch", null);
        Response res = UserResource.validatePasswordChange(user, req);
        assertNotNull(res);
        assertEquals(400, res.getStatus());
        assertEquals("Current password is incorrect", res.getEntity());
    }

    @Test
    void rejectsShortNewPasswordWhenCurrentPasswordIsValid() {
        UserRequest req = new UserRequest(null, null, "Ab1!", CURRENT_PASSWORD, "Ab1!", null);
        Response res = UserResource.validatePasswordChange(user, req);
        assertNotNull(res);
        assertEquals(400, res.getStatus());
        assertTrue(res.getEntity().toString().contains("8 characters"));
    }

    @Test
    void rejectsNewPasswordMissingUppercase() {
        UserRequest req = new UserRequest(null, null, "lowercase123", CURRENT_PASSWORD, "lowercase123", null);
        Response res = UserResource.validatePasswordChange(user, req);
        assertNotNull(res);
        assertEquals(400, res.getStatus());
        assertTrue(res.getEntity().toString().contains("uppercase"));
    }

    @Test
    void rejectsNewPasswordMissingLowercase() {
        UserRequest req = new UserRequest(null, null, "UPPERCASE123", CURRENT_PASSWORD, "UPPERCASE123", null);
        Response res = UserResource.validatePasswordChange(user, req);
        assertNotNull(res);
        assertEquals(400, res.getStatus());
        assertTrue(res.getEntity().toString().contains("lowercase"));
    }

    @Test
    void rejectsNewPasswordMissingNumber() {
        UserRequest req = new UserRequest(null, null, "NoNumbersHere", CURRENT_PASSWORD, "NoNumbersHere", null);
        Response res = UserResource.validatePasswordChange(user, req);
        assertNotNull(res);
        assertEquals(400, res.getStatus());
        assertTrue(res.getEntity().toString().contains("number"));
    }

    @Test
    void rejectsMismatchedPasswordVerification() {
        UserRequest req = new UserRequest(null, null, "ValidPass1", CURRENT_PASSWORD, "ValidPass2", null);
        Response res = UserResource.validatePasswordChange(user, req);
        assertNotNull(res);
        assertEquals(400, res.getStatus());
        assertEquals("Passwords do not match", res.getEntity());
    }

    @Test
    void acceptsValidPasswordWithoutSpecialCharacters() {
        UserRequest req = new UserRequest(null, null, "ValidPass1", CURRENT_PASSWORD, "ValidPass1", null);
        assertNull(UserResource.validatePasswordChange(user, req));
    }

    @Test
    void acceptsValidPasswordWithOptionalSpecialCharacters() {
        UserRequest req = new UserRequest(null, null, "ValidPass1!@#", CURRENT_PASSWORD, "ValidPass1!@#", null);
        assertNull(UserResource.validatePasswordChange(user, req));
    }
}
