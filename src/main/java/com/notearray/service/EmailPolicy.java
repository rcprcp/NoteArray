package com.notearray.service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalization and liberal format validation for user emails and names.
 *
 * <p>Emails are trimmed and lowercased so {@code User@Example.com } and
 * {@code user@example.com} resolve to the same canonical address. Format
 * validation checks for {@code local@domain.tld} structure ({@code something@x.y})
 * with valid non-empty tokens, a single {@code @}, and at least one {@code .} in
 * the domain.
 */
public final class EmailPolicy {

    public static final int MAX_EMAIL_LENGTH = 320;
    public static final int MAX_LOCAL_LENGTH = 64;
    public static final int MAX_DOMAIN_LENGTH = 255;
    public static final int MAX_LABEL_LENGTH = 63;
    public static final int MAX_NAME_LENGTH = 255;

    private static final Pattern LOCAL_PART = Pattern.compile("^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+$");
    private static final Pattern DOMAIN_LABEL = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\u0000-\\u001F\\u007F]");

    private EmailPolicy() {
    }

    /** Trim leading/trailing whitespace and lowercase the email address. */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    /** Trim leading/trailing whitespace from a user name. */
    public static String normalizeName(String name) {
        return name == null ? null : name.strip();
    }

    /** True if the email is valid after normalization. */
    public static boolean isValidEmail(String email) {
        return validateEmail(email) == null;
    }

    /**
     * Validate an email address. Returns {@code null} when valid, or a
     * human-readable error message when invalid.
     */
    public static String validateEmail(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        if (email == null || email.isEmpty()) {
            return "email is required";
        }
        if (email.length() > MAX_EMAIL_LENGTH) {
            return "email must be at most " + MAX_EMAIL_LENGTH + " characters";
        }
        if (CONTROL_CHARS.matcher(email).find() || email.chars().anyMatch(Character::isWhitespace)) {
            return "invalid email format";
        }

        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1) {
            return "invalid email format";
        }

        String local = email.substring(0, at);
        String domain = email.substring(at + 1);

        if (local.length() > MAX_LOCAL_LENGTH
                || local.startsWith(".")
                || local.endsWith(".")
                || local.contains("..")
                || !LOCAL_PART.matcher(local).matches()) {
            return "invalid email format";
        }

        if (domain.length() > MAX_DOMAIN_LENGTH
                || !domain.contains(".")
                || domain.startsWith(".")
                || domain.endsWith(".")
                || domain.contains("..")) {
            return "invalid email format";
        }

        String[] labels = domain.split("\\.");
        if (labels.length < 2) {
            return "invalid email format";
        }
        for (String label : labels) {
            if (label.isEmpty()
                    || label.length() > MAX_LABEL_LENGTH
                    || !DOMAIN_LABEL.matcher(label).matches()) {
                return "invalid email format";
            }
        }
        // Final TLD label must contain at least one letter (not purely digits)
        String tld = labels[labels.length - 1];
        boolean hasLetter = false;
        for (int i = 0; i < tld.length(); i++) {
            char c = tld.charAt(i);
            if (c >= 'a' && c <= 'z') {
                hasLetter = true;
                break;
            }
        }
        if (!hasLetter) {
            return "invalid email format";
        }

        return null;
    }

    /**
     * Validate a user display name. Returns {@code null} when valid, or a
     * human-readable error message when invalid.
     */
    public static String validateName(String rawName) {
        String name = normalizeName(rawName);
        if (name == null || name.isEmpty()) {
            return "name is required";
        }
        if (name.length() > MAX_NAME_LENGTH) {
            return "name must be at most " + MAX_NAME_LENGTH + " characters";
        }
        if (CONTROL_CHARS.matcher(name).find()) {
            return "invalid name";
        }
        return null;
    }
}
