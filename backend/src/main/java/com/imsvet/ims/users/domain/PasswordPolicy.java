package com.imsvet.ims.users.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ADR-039: a minimum length, no composition rules, and no password from the common-password list
 * (compared case-insensitively). Returns violation codes instead of throwing, so callers choose the field.
 */
public final class PasswordPolicy {

    public static final String TOO_SHORT = "TOO_SHORT";
    public static final String TOO_COMMON = "TOO_COMMON";

    private final int minLength;
    private final Set<String> commonPasswords;

    public PasswordPolicy(int minLength, Set<String> commonPasswords) {
        this.minLength = minLength;
        this.commonPasswords = Set.copyOf(commonPasswords);
    }

    public List<String> violations(String password) {
        List<String> violations = new ArrayList<>();
        if (password == null || password.codePointCount(0, password.length()) < minLength) {
            violations.add(TOO_SHORT);
        } else if (commonPasswords.contains(password.toLowerCase(Locale.ROOT))) {
            violations.add(TOO_COMMON);
        }
        return violations;
    }

    public int minLength() {
        return minLength;
    }
}
