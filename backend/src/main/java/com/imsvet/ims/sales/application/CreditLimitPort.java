package com.imsvet.ims.sales.application;

import java.math.BigDecimal;

/**
 * The customer's credit as {@code finance} knows it (architecture.md §3); defined here so that sales never depends on
 * finance. Joins the caller's transaction, and the caller checks its permission.
 */
public interface CreditLimitPort {

    /** {@code balance} per BR-PAY-004; the uninvoiced credit delivery notes count in the check only (ADR-117). */
    record CreditPosition(BigDecimal balance, BigDecimal uninvoicedDeliveryNotes) {
    }

    CreditPosition position(long customerId);

    /**
     * Refuses a credit sale of {@code ttc} with {@code CREDIT_LIMIT_EXCEEDED} (BR-PAY-005). The caller holds the customer
     * row lock, so two sales near the limit are checked one after the other. {@code limit} {@code null} = no limit.
     */
    void requireWithinLimit(long customerId, BigDecimal limit, BigDecimal ttc);
}
