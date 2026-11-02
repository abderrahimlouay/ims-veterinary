package com.imsvet.ims.sales.application;

import com.imsvet.ims.sales.application.PaymentRecorder.Tender;
import java.math.BigDecimal;
import java.util.List;

/**
 * The port through which a customer return pays back the credit note of an immediate sale (ADR-128); implemented by
 * {@code finance}, the only writer of money. Joins the caller's transaction, and the caller checks its permission.
 */
public interface RefundRecorder {

    /**
     * {@code customerId} {@code null} on an anonymous sale; {@code originalMethods} are the payment methods of the
     * original document, recorded in the audit.
     */
    record RefundRequest(long creditNoteId, long originalDocumentId, Long customerId, BigDecimal creditNoteTtc,
            List<Tender> tenders, List<String> originalMethods) {
    }

    record RecordedRefund(long id, String method, BigDecimal amount, String reference, Long cashSessionId) {
    }

    /**
     * Pays back exactly {@code creditNoteTtc} with {@code tenders} ({@code REFUND_TOTAL_MISMATCH} otherwise); a cash part
     * needs the user's own open session and enough expected cash in it ({@code CASH_INSUFFICIENT}).
     */
    List<RecordedRefund> refund(RefundRequest request);

    /** The refunds of a credit note, oldest first. */
    List<RecordedRefund> refundsOf(long creditNoteId);
}
