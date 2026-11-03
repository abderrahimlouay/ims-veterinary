package com.imsvet.ims.finance.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** The payments on account and what is still open (api.md §4.9, §4.12, §5.9; ADR-134). */
public final class AccountPaymentViews {

    private AccountPaymentViews() {
    }

    public record CustomerRef(long id, String name) {
    }

    public record AllocationView(long id, long invoiceId, String invoiceNumber, BigDecimal amount, Instant createdAt) {
    }

    /**
     * {@code reverses}: the payment this row reverses; {@code reversedBy}: the row reversing this one.
     * {@code customerBalance}: the balance of ADR-118, {@code null} for an anonymous counter payment.
     */
    public record PaymentDetail(long id, CustomerRef customer, String method, BigDecimal amount, String reference,
            Long cashSessionId, Instant paidAt, Long reverses, Long reversedBy, List<AllocationView> allocations,
            BigDecimal unallocated, BigDecimal customerBalance) {
    }

    public record PaymentSummary(long id, Long customerId, String method, BigDecimal amount, String reference,
            Instant paidAt, Long reverses, boolean reversed) {
    }

    /** {@code open}: TTC − its credit notes − what payments in force allocate to it, never below zero. */
    public record OpenInvoice(long id, String number, Instant issuedAt, LocalDate dueDate, BigDecimal totalTtc,
            BigDecimal open) {
    }

    public record Advance(long paymentId, Instant paidAt, BigDecimal unallocated) {
    }

    public record OpenItems(List<OpenInvoice> invoices, List<Advance> advances) {
    }
}
