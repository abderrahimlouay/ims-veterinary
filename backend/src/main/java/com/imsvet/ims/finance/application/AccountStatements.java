package com.imsvet.ims.finance.application;

import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.finance.domain.CustomerPayment;
import com.imsvet.ims.finance.domain.PaymentAllocation;
import com.imsvet.ims.finance.infrastructure.CustomerPaymentRepository;
import com.imsvet.ims.finance.infrastructure.PaymentAllocationRepository;
import com.imsvet.ims.sales.application.CreditDocuments;
import com.imsvet.ims.sales.application.CreditDocuments.AccountHolder;
import com.imsvet.ims.sales.application.CreditDocuments.CreditEntry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The account statement (BR-PAY-007, ADR-134 item 6, W8 step 4): the opening balance at the start date, then the
 * entries of the balance of ADR-118 in date order with a running balance. Debits: confirmed credit invoices and the
 * reversals of payments on account; credits: their credit notes and payments on account. Counter sales and their
 * payments are not listed; a payment and its reversal both appear and cancel out, so the closing balance on the last
 * day equals the balance ({@link CreditAccounts#position}). Uninvoiced credit delivery notes are given apart (ADR-117).
 */
@Service
public class AccountStatements {

    private static final int MONEY_SCALE = 2;
    private static final Comparator<Line> CHRONOLOGICAL = Comparator.comparing(Line::at)
            .thenComparing(Line::order).thenComparing(Line::id);

    public record StatementCustomer(long id, String name) {
    }

    /**
     * {@code kind}: {@code invoice}, {@code credit_note}, {@code payment} or {@code reversal}; documents carry
     * {@code documentId} and {@code number}, payments {@code paymentId}, {@code method} and {@code reference}.
     */
    public record StatementLine(LocalDate date, Instant at, String kind, Long documentId, Long paymentId, String number,
            String method, String reference, BigDecimal debit, BigDecimal credit, BigDecimal balance) {
    }

    /** {@code from} is {@code null} when the statement starts at the first entry; {@code to} defaults to today. */
    public record Statement(StatementCustomer customer, LocalDate from, LocalDate to, BigDecimal openingBalance,
            List<StatementLine> lines, BigDecimal totalDebit, BigDecimal totalCredit, BigDecimal closingBalance,
            BigDecimal uninvoicedDeliveryNotes) {
    }

    private record Line(Instant at, int order, long id, String kind, Long documentId, Long paymentId, String number,
            String method, String reference, BigDecimal debit, BigDecimal credit) {

        BigDecimal signed() {
            return debit.subtract(credit);
        }
    }

    private final CreditDocuments documents;
    private final CustomerPaymentRepository payments;
    private final PaymentAllocationRepository allocations;
    private final BusinessCalendar calendar;

    public AccountStatements(CreditDocuments documents, CustomerPaymentRepository payments,
            PaymentAllocationRepository allocations, BusinessCalendar calendar) {
        this.documents = documents;
        this.payments = payments;
        this.allocations = allocations;
        this.calendar = calendar;
    }

    /** Business dates, both inclusive; {@code VALIDATION_FAILED} when {@code from} is after {@code to}. */
    @PreAuthorize("hasAuthority('statement.read')")
    @Transactional(readOnly = true)
    public Statement statement(long customerId, LocalDate from, LocalDate to) {
        LocalDate last = to == null ? calendar.today() : to;
        if (from != null && from.isAfter(last)) {
            throw new ValidationException("The period is invalid.",
                    List.of(new Violation("from", "RANGE", "The start date must not be after the end date.")));
        }
        AccountHolder holder = documents.holder(customerId);
        List<Line> entries = entries(customerId);
        BigDecimal opening = zero();
        BigDecimal running;
        List<StatementLine> lines = new ArrayList<>();
        BigDecimal debit = zero();
        BigDecimal credit = zero();
        for (Line line : entries) {
            LocalDate date = calendar.dateOf(line.at());
            if (date.isAfter(last)) {
                break;
            }
            if (from != null && date.isBefore(from)) {
                opening = opening.add(line.signed());
                continue;
            }
            debit = debit.add(line.debit());
            credit = credit.add(line.credit());
            running = opening.add(debit).subtract(credit);
            lines.add(new StatementLine(date, line.at(), line.kind(), line.documentId(), line.paymentId(),
                    line.number(), line.method(), line.reference(), line.debit(), line.credit(), running));
        }
        return new Statement(new StatementCustomer(holder.id(), holder.name()), from, last, opening, lines, debit,
                credit, opening.add(debit).subtract(credit),
                documents.totals(customerId).uninvoicedDeliveryNotes().setScale(MONEY_SCALE));
    }

    private List<Line> entries(long customerId) {
        List<Line> entries = new ArrayList<>();
        for (CreditEntry entry : documents.creditEntries(customerId)) {
            boolean invoice = "invoice".equals(entry.kind());
            BigDecimal amount = entry.totalTtc().setScale(MONEY_SCALE);
            entries.add(new Line(entry.issuedAt(), invoice ? 0 : 1, entry.id(), entry.kind(), entry.id(), null,
                    entry.number(), null, null, invoice ? amount : zero(), invoice ? zero() : amount));
        }
        List<CustomerPayment> all = payments.findByCustomerIdOrderByIdAsc(customerId);
        Set<Long> onAccount = onAccount(customerId, all);
        for (CustomerPayment payment : all) {
            boolean reversal = payment.reversesPaymentId() != null;
            if (!onAccount.contains(reversal ? payment.reversesPaymentId() : payment.id())) {
                continue;
            }
            entries.add(new Line(payment.paidAt(), reversal ? 3 : 2, payment.id(), reversal ? "reversal" : "payment",
                    null, payment.id(), null, payment.method().code(), payment.reference(),
                    reversal ? payment.amount() : zero(), reversal ? zero() : payment.amount()));
        }
        entries.sort(CHRONOLOGICAL);
        return entries;
    }

    /** Payments on account: not reversals, allocated to credit documents only or not at all (ADR-118, ADR-134). */
    private Set<Long> onAccount(long customerId, List<CustomerPayment> all) {
        Set<Long> credit = new HashSet<>(documents.creditDocumentIds(customerId));
        List<Long> ids = all.stream().filter(p -> p.reversesPaymentId() == null).map(CustomerPayment::id).toList();
        Map<Long, Boolean> counter = new HashMap<>();
        if (!ids.isEmpty()) {
            for (PaymentAllocation a : allocations.findByPaymentIdInOrderByIdAsc(ids)) {
                if (!credit.contains(a.invoiceId())) {
                    counter.put(a.paymentId(), true);
                }
            }
        }
        Set<Long> onAccount = new HashSet<>();
        ids.stream().filter(id -> !counter.containsKey(id)).forEach(onAccount::add);
        return onAccount;
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(MONEY_SCALE);
    }
}
