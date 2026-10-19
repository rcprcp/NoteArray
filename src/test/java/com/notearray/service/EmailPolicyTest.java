package com.notearray.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EmailPolicyTest {

    @Test
    void normalizesEmailByTrimmingAndLowercasing() {
        assertEquals("user@example.com", EmailPolicy.normalizeEmail("  User@Example.COM \t\n"));
        assertNull(EmailPolicy.normalizeEmail(null));
    }

    @Test
    void normalizesNameByTrimming() {
        assertEquals("Alice Smith", EmailPolicy.normalizeName("  Alice Smith  "));
        assertNull(EmailPolicy.normalizeName(null));
    }

    @Test
    void acceptsValidLiberalEmails() {
        assertTrue(EmailPolicy.isValidEmail("something@x.y"));
        assertTrue(EmailPolicy.isValidEmail("  USER@Example.com  "));
        assertTrue(EmailPolicy.isValidEmail("first.last+tag_1@sub.domain.co.uk"));
        assertTrue(EmailPolicy.isValidEmail("a@b.c"));
    }

    @Test
    void rejectsMissingOrMalformedEmails() {
        assertEquals("email is required", EmailPolicy.validateEmail(null));
        assertEquals("email is required", EmailPolicy.validateEmail("   "));
        assertFalse(EmailPolicy.isValidEmail("abc"));
        assertFalse(EmailPolicy.isValidEmail("user@"));
        assertFalse(EmailPolicy.isValidEmail("@x.y"));
        assertFalse(EmailPolicy.isValidEmail("user@@x.y"));
        assertFalse(EmailPolicy.isValidEmail("user@localhost"));
        assertFalse(EmailPolicy.isValidEmail(".user@x.y"));
        assertFalse(EmailPolicy.isValidEmail("user.@x.y"));
        assertFalse(EmailPolicy.isValidEmail("us..er@x.y"));
        assertFalse(EmailPolicy.isValidEmail("user@.x.y"));
        assertFalse(EmailPolicy.isValidEmail("user@x.y."));
        assertFalse(EmailPolicy.isValidEmail("user@x..y"));
        assertFalse(EmailPolicy.isValidEmail("user@-x.y"));
        assertFalse(EmailPolicy.isValidEmail("user@x-.y"));
        assertFalse(EmailPolicy.isValidEmail("user@x.123"));
        assertFalse(EmailPolicy.isValidEmail("user name@x.y"));
    }

    @Test
    void rejectsOversizedEmail() {
        String longDomain = "a".repeat(60) + "." + "b".repeat(60) + "." + "c".repeat(60)
                + "." + "d".repeat(60) + "." + "e".repeat(60) + "." + "f".repeat(60) + ".com";
        String email = "user@" + longDomain;
        String err = EmailPolicy.validateEmail(email);
        assertNotNull(err);
        assertTrue(err.contains("320"));
    }

    @Test
    void validatesName() {
        assertNull(EmailPolicy.validateName("Alice"));
        assertNull(EmailPolicy.validateName("  Alice Bob  "));
        assertEquals("name is required", EmailPolicy.validateName(null));
        assertEquals("name is required", EmailPolicy.validateName("   "));
        assertNotNull(EmailPolicy.validateName("a".repeat(256)));
        assertEquals("invalid name", EmailPolicy.validateName("Alice\u0007"));
    }
}
