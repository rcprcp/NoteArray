package com.notearray.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validates user passwords against the application's policy.
 *
 * <p>Required rules: at least eight characters, at least one upper case, at least one
 * lower case, and at least one number. Special characters from the set
 * {@code !@#$%^&*} are optional (the password does not have to include one).
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    private static final Pattern UPPER = Pattern.compile("[A-Z]");
    private static final Pattern LOWER = Pattern.compile("[a-z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");
    private static final Pattern SPECIAL = Pattern.compile("[!@#$%^&*]");

    private PasswordPolicy() {
    }

    /**
     * Check if the candidate password meets the policy. Returns an empty list if
     * it is valid; otherwise returns human-readable failure messages.
     */
    public static List<String> validate(String password) {
        List<String> failures = new ArrayList<>();
        if (password == null) {
            failures.add("password is required");
            return failures;
        }
        if (password.length() < MIN_LENGTH) {
            failures.add("password must be at least " + MIN_LENGTH + " characters");
        }
        if (!UPPER.matcher(password).find()) {
            failures.add("password must contain at least one uppercase letter");
        }
        if (!LOWER.matcher(password).find()) {
            failures.add("password must contain at least one lowercase letter");
        }
        if (!DIGIT.matcher(password).find()) {
            failures.add("password must contain at least one number");
        }
        // Special characters are optional and only those in !@#$%^&* count if present,
        // but they are not required. No failure is added here.
        return failures;
    }

    /** True when {@code password} contains any of {@code !@#$%^&*}. */
    public static boolean hasSpecial(String password) {
        return password != null && SPECIAL.matcher(password).find();
    }

    /**
     * Return a short, client-friendly summary of the policy. The UI uses this to
     * guide the user (specials shown as optional).
     */
    public static String policyDescription() {
        return "Password must be at least 8 characters and include at least one uppercase letter, "
                + "one lowercase letter, and one number. Special characters (!@#$%^&*) are optional.";
    }
}
