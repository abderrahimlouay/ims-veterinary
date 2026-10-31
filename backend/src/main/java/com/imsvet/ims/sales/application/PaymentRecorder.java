package com.imsvet.ims.sales.application;

import java.math.BigDecimal;
import java.util.List;

/**
 * The port through which a sale records its money (ADR-118); implemented by {@code finance}, the only writer of
 * payments. Joins the caller's transaction, and the caller checks its permission.
 */
public interface PaymentRecorder {

    /** One way of paying; {@code amount} is what is kept, never what was tendered (ADR-118). */
    record Tender(String method, BigDecimal amount, String reference) {
    }

    /** {@code cashSessionId} {@code null} when the payment has no cash part. */
    record RecordedPayments(Long cashSessionId, List<Long> paymentIds) {
    }

    public record PaymentInForce(long id, String method, BigDecimal amount, String reference) {
    }

    /**
     * Pays a document with {@code tenders}, at most {@code documentTtc}; a cash part needs the user's open session.
     * {@code customerId} {@code null} on an anonymous sale.
     */
    RecordedPayments record(long documentId, Long customerId, BigDecimal documentTtc, List<Tender> tenders);

    boolean sessionOpen(long cashSessionId);

    /** What is still in force on a document, oldest first. */
    List<PaymentInForce> inForceOn(long documentId);

    /** Reverses what is still in force on a document, cash going out of the same session; returns the reversals. */
    List<Long> reverseAll(long documentId, String reason);
}
