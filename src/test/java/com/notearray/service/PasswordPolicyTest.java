package com.notearray.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void acceptsAValidPasswordWithSpecialCharacter() {
        assertTrue(PasswordPolicy.validate("Abcd1234!").isEmpty());
        assertTrue(PasswordPolicy.hasSpecial("Abcd1234!"));
    }

    @Test
    void acceptsAValidPasswordWithoutSpecialCharacter() {
        // Specials are optional per the policy.
        assertTrue(PasswordPolicy.validate("Abcd1234").isEmpty());
        assertFalse(PasswordPolicy.hasSpecial("Abcd1234"));
    }

    @Test
    void rejectsShortPasswords() {
        List<String> failures = PasswordPolicy.validate("Ab1");
        assertFalse(failures.isEmpty());
        assertTrue(failures.stream().anyMatch(m -> m.contains("8 characters")));
    }

    @Test
    void rejectsMissingUppercase() {
        List<String> failures = PasswordPolicy.validate("abcd1234");
        assertTrue(failures.stream().anyMatch(m -> m.contains("uppercase")));
    }

    @Test
    void rejectsMissingLowercase() {
        List<String> failures = PasswordPolicy.validate("ABCD1234");
        assertTrue(failures.stream().anyMatch(m -> m.contains("lowercase")));
    }

    @Test
    void rejectsMissingDigit() {
        List<String> failures = PasswordPolicy.validate("Abcdefgh");
        assertTrue(failures.stream().anyMatch(m -> m.contains("number")));
    }

    @Test
    void treatsNullAsInvalid() {
        List<String> failures = PasswordPolicy.validate(null);
        assertFalse(failures.isEmpty());
        assertTrue(failures.contains("password is required"));
    }

    @Test
    void allowsAllSpecifiedSpecials() {
        assertTrue(PasswordPolicy.validate("Abcd1234!").isEmpty());
        assertTrue(PasswordPolicy.validate("Abcd1234@").isEmpty());
        assertTrue(PasswordPolicy.validate("Abcd1234#").isEmpty());
        assertTrue(PasswordPolicy.validate("Abcd1234$").isEmpty());
        assertTrue(PasswordPolicy.validate("Abcd1234%").isEmpty());
        assertTrue(PasswordPolicy.validate("Abcd1234^").isEmpty());
        assertTrue(PasswordPolicy.validate("Abcd1234&").isEmpty());
        assertTrue(PasswordPolicy.validate("Abcd1234*").isEmpty());
    }

    @Test
    void policyDescriptionIsStable() {
        String desc = PasswordPolicy.policyDescription();
        assertTrue(desc.contains("8 characters"));
        assertTrue(desc.contains("optional"));
    }
}
