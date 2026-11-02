package com.imsvet.ims.finance.application;

import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.finance.domain.PaymentMethod;
import com.imsvet.ims.sales.application.PaymentRecorder.Tender;
import java.math.BigDecimal;
import java.util.List;

/** BR-PAY-001 for one tender of a payment or a refund: a known method, an amount > 0 with 2 decimals, a reference unless cash. */
final class Tenders {

    static final int MONEY_SCALE = 2;
    private static final int REFERENCE_MAX = 80;

    private Tenders() {
    }

    /** {@code subject} names the refused record in the message, e.g. "The payment is invalid.". */
    static PaymentMethod validate(Tender tender, String prefix, String subject) {
        PaymentMethod method = tender.method() == null ? null : PaymentMethod.fromCode(tender.method())
                .orElseThrow(() -> invalid(subject, prefix + "method", "UNKNOWN", "Unknown payment method."));
        if (method == null) {
            throw invalid(subject, prefix + "method", "REQUIRED", "The payment method is required.");
        }
        BigDecimal amount = tender.amount();
        if (amount == null) {
            throw invalid(subject, prefix + "amount", "REQUIRED", "The amount is required.");
        }
        if (amount.signum() <= 0) {
            throw invalid(subject, prefix + "amount", "POSITIVE", "The amount must be greater than zero.");
        }
        if (amount.stripTrailingZeros().scale() > MONEY_SCALE) {
            throw invalid(subject, prefix + "amount", "SCALE", "At most two decimals.");
        }
        String reference = reference(tender);
        if (method != PaymentMethod.CASH && reference == null) {
            throw invalid(subject, prefix + "reference", "REQUIRED", "A cheque or a transfer needs its reference.");
        }
        if (reference != null && reference.length() > REFERENCE_MAX) {
            throw invalid(subject, prefix + "reference", "SIZE", "At most " + REFERENCE_MAX + " characters.");
        }
        return method;
    }

    static String reference(Tender tender) {
        return tender.reference() == null || tender.reference().isBlank() ? null : tender.reference().trim();
    }

    private static ValidationException invalid(String subject, String field, String code, String message) {
        return new ValidationException(subject, List.of(new Violation(field, code, message)));
    }
}
