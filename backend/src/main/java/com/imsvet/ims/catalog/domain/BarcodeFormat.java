package com.imsvet.ims.catalog.domain;

import java.util.Optional;

/**
 * BR-PROD-008 and ADR-067: every code is non-blank and at most 64 characters; EAN13 is 13 ASCII digits with a valid
 * check digit; CODE128 is printable ASCII (32–126); QR is any text without control characters. No other check.
 */
public final class BarcodeFormat {

    public static final int MAX_LENGTH = 64;

    /** The field error codes of api.md §4.3. */
    public enum Problem {
        REQUIRED, SIZE, FORMAT, CHECK_DIGIT
    }

    private BarcodeFormat() {
    }

    public static Optional<Problem> check(BarcodeType type, String code) {
        if (code == null || code.isBlank()) {
            return Optional.of(Problem.REQUIRED);
        }
        if (code.codePointCount(0, code.length()) > MAX_LENGTH) {
            return Optional.of(Problem.SIZE);
        }
        return switch (type) {
            case EAN13 -> ean13(code);
            case CODE128 -> code.chars().allMatch(c -> c >= 32 && c <= 126) ? Optional.empty() : Optional.of(Problem.FORMAT);
            case QR -> code.codePoints().noneMatch(Character::isISOControl) ? Optional.empty() : Optional.of(Problem.FORMAT);
        };
    }

    /** GS1 check digit: weights 1 and 3 alternately from the left over the first 12 digits. */
    private static Optional<Problem> ean13(String code) {
        if (code.length() != 13 || !code.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return Optional.of(Problem.FORMAT);
        }
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (code.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        int check = (10 - sum % 10) % 10;
        return check == code.charAt(12) - '0' ? Optional.empty() : Optional.of(Problem.CHECK_DIGIT);
    }
}
