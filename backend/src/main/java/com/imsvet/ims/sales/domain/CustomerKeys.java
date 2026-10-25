package com.imsvet.ims.sales.domain;

import java.util.Locale;

/**
 * The normalised keys behind duplicate detection (ADR-110). A phone loses its separators and the Algerian
 * international or trunk prefix ({@code +213}, {@code 00213}, a leading {@code 0}), so every way of writing one
 * number gives the same national number; a tax id is trimmed, upper-cased and loses its spaces. A value without
 * content has no key, and a missing key never conflicts.
 */
public final class CustomerKeys {

    private CustomerKeys() {
    }

    public static String phoneKey(String phone) {
        if (phone == null) {
            return null;
        }
        String compact = phone.replaceAll("[\\s.\\-()\\u00a0]", "");
        if (compact.startsWith("+213")) {
            compact = compact.substring(4);
        } else if (compact.startsWith("00213")) {
            compact = compact.substring(5);
        }
        String digits = compact.replaceAll("\\D", "");
        while (digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return digits.isEmpty() ? null : digits;
    }

    public static String taxKey(String taxId) {
        if (taxId == null) {
            return null;
        }
        String key = taxId.replaceAll("[\\s\\u00a0]", "").toUpperCase(Locale.ROOT);
        return key.isEmpty() ? null : key;
    }
}
