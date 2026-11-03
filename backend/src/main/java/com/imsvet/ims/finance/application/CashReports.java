package com.imsvet.ims.finance.application;

import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.security.Permissions;
import com.imsvet.ims.finance.domain.CashAmounts;
import com.imsvet.ims.finance.domain.CashDirection;
import com.imsvet.ims.finance.domain.CashMovement;
import com.imsvet.ims.finance.domain.CashSession;
import com.imsvet.ims.finance.domain.CustomerPayment;
import com.imsvet.ims.finance.domain.PaymentMethod;
import com.imsvet.ims.finance.domain.SupplierPayment;
import com.imsvet.ims.finance.infrastructure.CashMovementRepository;
import com.imsvet.ims.finance.infrastructure.CashSessionRepository;
import com.imsvet.ims.finance.infrastructure.CustomerPaymentRepository;
import com.imsvet.ims.finance.infrastructure.CustomerRefundRepository;
import com.imsvet.ims.finance.infrastructure.PaymentAllocationRepository;
import com.imsvet.ims.finance.infrastructure.SupplierPaymentRepository;
import com.imsvet.ims.sales.application.CreditDocuments;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The session report (ADR-136 item 3, BR-CASH-007, W9 step 4), on screen in Phase 9: the opening amount, the cash
 * movements totalled by source (sales, payments on account, refunds, supplier payments, reversals, manual), the
 * expected, counted and variance; and, apart and outside the expected cash (BR-CASH-003), the cheques and transfers the
 * session's cashier recorded while it was open. {@code cash.manage}, or the cashier for their own session.
 */
@Service
public class CashReports {

    private static final String MANAGE = "cash.manage";
    private static final int MONEY_SCALE = 2;
    private static final List<String> SOURCES = List.of("sales", "account_payments", "refunds", "supplier_payments",
            "reversals", "manual");

    public record SourceTotal(String source, BigDecimal in, BigDecimal out) {
    }

    /**
     * {@code kind}: {@code customer_payment}, {@code customer_refund} or {@code supplier_payment}; {@code direction} as
     * for cash (a customer payment comes in, a refund or a supplier payment goes out, a reversal the other way).
     */
    public record NonCashEntry(String kind, long id, boolean reversal, String method, String reference,
            BigDecimal amount, String direction, Instant at) {
    }

    public record CashReport(CashSessionView session, Long closedBy, BigDecimal openingAmount,
            List<SourceTotal> bySource, List<NonCashEntry> nonCash, BigDecimal expected, BigDecimal counted,
            BigDecimal variance) {
    }

    private final CashSessionRepository sessions;
    private final CashMovementRepository movements;
    private final CustomerPaymentRepository customerPayments;
    private final PaymentAllocationRepository allocations;
    private final CustomerRefundRepository refunds;
    private final SupplierPaymentRepository supplierPayments;
    private final CreditDocuments documents;
    private final ActorProvider actors;
    private final Clock clock;

    public CashReports(CashSessionRepository sessions, CashMovementRepository movements,
            CustomerPaymentRepository customerPayments, PaymentAllocationRepository allocations,
            CustomerRefundRepository refunds, SupplierPaymentRepository supplierPayments, CreditDocuments documents,
            ActorProvider actors, Clock clock) {
        this.sessions = sessions;
        this.movements = movements;
        this.customerPayments = customerPayments;
        this.allocations = allocations;
        this.refunds = refunds;
        this.supplierPayments = supplierPayments;
        this.documents = documents;
        this.actors = actors;
        this.clock = clock;
    }

    @PreAuthorize("hasAnyAuthority('cash.open', 'cash.manage')")
    @Transactional(readOnly = true)
    public CashReport report(long id) {
        CashSession session = sessions.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "Cash session not found.", Map.of("id", id)));
        if (!session.cashierId().equals(actors.current().userId()) && !Permissions.granted(MANAGE)) {
            throw new AccessDeniedException("Another user's session report needs cash.manage");
        }
        Map<String, BigDecimal[]> totals = new LinkedHashMap<>();
        SOURCES.forEach(s -> totals.put(s, new BigDecimal[] { zero(), zero() }));
        BigDecimal in = BigDecimal.ZERO;
        BigDecimal out = BigDecimal.ZERO;
        for (CashMovement movement : movements.findByCashSessionIdOrderByIdAsc(id)) {
            if (movement.method() != PaymentMethod.CASH) {
                continue;
            }
            BigDecimal[] total = totals.get(source(movement));
            if (movement.direction() == CashDirection.IN) {
                total[0] = total[0].add(movement.amount());
                in = in.add(movement.amount());
            } else {
                total[1] = total[1].add(movement.amount());
                out = out.add(movement.amount());
            }
        }
        BigDecimal expected = session.open()
                ? CashAmounts.expected(session.openingAmount(), in, out).setScale(MONEY_SCALE)
                : session.expectedAmount();
        List<SourceTotal> bySource = totals.entrySet().stream()
                .map(e -> new SourceTotal(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
        return new CashReport(CashSessionView.of(session, expected), session.closedBy(), session.openingAmount(),
                bySource, nonCash(session), expected, session.countedAmount(), session.varianceAmount());
    }

    private String source(CashMovement movement) {
        if (movement.manual()) {
            return "manual";
        }
        if (movement.customerRefundId() != null) {
            return "refunds";
        }
        if (movement.supplierPaymentId() != null) {
            return supplierPayments.findById(movement.supplierPaymentId()).orElseThrow().reversesPaymentId() != null
                    ? "reversals" : "supplier_payments";
        }
        CustomerPayment payment = customerPayments.findById(movement.customerPaymentId()).orElseThrow();
        if (payment.reversesPaymentId() != null) {
            return "reversals";
        }
        return onAccount(payment) ? "account_payments" : "sales";
    }

    /** A payment allocated to credit documents only, or not at all (ADR-118, ADR-134). */
    private boolean onAccount(CustomerPayment payment) {
        if (payment.customerId() == null) {
            return false;
        }
        Set<Long> credit = new HashSet<>(documents.creditDocumentIds(payment.customerId()));
        return allocations.findByPaymentIdOrderByIdAsc(payment.id()).stream()
                .allMatch(a -> credit.contains(a.invoiceId()));
    }

    private List<NonCashEntry> nonCash(CashSession session) {
        Instant from = session.openedAt();
        Instant to = session.closedAt() != null ? session.closedAt() : clock.instant();
        long cashier = session.cashierId();
        List<NonCashEntry> entries = new ArrayList<>();
        for (CustomerPayment p : customerPayments.findByCreatedByAndPaidAtBetweenOrderByIdAsc(cashier, from, to)) {
            if (p.method() != PaymentMethod.CASH) {
                boolean reversal = p.reversesPaymentId() != null;
                entries.add(new NonCashEntry("customer_payment", p.id(), reversal, p.method().code(), p.reference(),
                        p.amount(), reversal ? "out" : "in", p.paidAt()));
            }
        }
        refunds.findByCreatedByAndRefundedAtBetweenOrderByIdAsc(cashier, from, to).stream()
                .filter(r -> r.method() != PaymentMethod.CASH)
                .forEach(r -> entries.add(new NonCashEntry("customer_refund", r.id(), false, r.method().code(),
                        r.reference(), r.amount(), "out", r.refundedAt())));
        for (SupplierPayment p : supplierPayments.findByCreatedByAndPaidAtBetweenOrderByIdAsc(cashier, from, to)) {
            if (p.method() != PaymentMethod.CASH) {
                boolean reversal = p.reversesPaymentId() != null;
                entries.add(new NonCashEntry("supplier_payment", p.id(), reversal, p.method().code(), p.reference(),
                        p.amount(), reversal ? "in" : "out", p.paidAt()));
            }
        }
        entries.sort(Comparator.comparing(NonCashEntry::at).thenComparing(NonCashEntry::id));
        return entries;
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(MONEY_SCALE);
    }
}
