package com.imsvet.ims.finance.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** BR-PAY-001. Only cash counts in the expected amount of a session (BR-CASH-003). */
public enum PaymentMethod {
    CASH, CHEQUE, BANK_TRANSFER;

    /** The stored and API value. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<PaymentMethod> fromCode(String code) {
        return Arrays.stream(values()).filter(m -> m.code().equals(code)).findFirst();
    }
}
