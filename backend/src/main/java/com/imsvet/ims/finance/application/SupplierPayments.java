package com.imsvet.ims.finance.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.idempotency.IdempotencyKeys;
import com.imsvet.ims.common.idempotency.IdempotencyKeys.Claim;
import com.imsvet.ims.common.paging.PageRequests;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.finance.domain.CashDirection;
import com.imsvet.ims.finance.domain.PaymentMethod;
import com.imsvet.ims.finance.domain.SupplierInvoice;
import com.imsvet.ims.finance.domain.SupplierPayment;
import com.imsvet.ims.finance.domain.SupplierPaymentAllocation;
import com.imsvet.ims.finance.infrastructure.SupplierInvoiceLocks;
import com.imsvet.ims.finance.infrastructure.SupplierInvoiceRepository;
import com.imsvet.ims.finance.infrastructure.SupplierPaymentAllocationRepository;
import com.imsvet.ims.finance.infrastructure.SupplierPaymentRepository;
import com.imsvet.ims.purchasing.application.SupplierAccounts;
import com.imsvet.ims.purchasing.application.SupplierAccounts.SupplierRef;
import com.imsvet.ims.sales.application.PaymentRecorder.Tender;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supplier payments (ADR-132, ADR-135, W1c, BR-PAY-009/010/013/014): a payment is allocated to one or several recorded
 * invoices of its supplier (as asked, or oldest due first) and what is left is an advance, applicable later by new rows;
 * cash goes out of the payer's own open session, within its expected cash; a payment is reversed once, with its reason,
 * the cash coming back into the reverser's own session. Lock order (database.md §7): idempotency key, the supplier, its
 * invoices in ascending id order, the cash session.
 */
@Service
public class SupplierPayments {

    private static final String RECORD_ENDPOINT = "POST /api/payments/suppliers";
    private static final String APPLY_ENDPOINT = "POST /api/payments/suppliers/{id}/allocations";
    private static final String REVERSE_ENDPOINT = "POST /api/payments/suppliers/{id}/reverse";
    private static final String PAYMENT_INVALID = "The payment is invalid.";
    private static final int REASON_MAX = 500;

    /** {@code allocations}: {@code null} = oldest due first; empty = all of it an advance. */
    public record PaymentInput(Long supplierId, String method, BigDecimal amount, String reference,
            List<AllocationInput> allocations) {
    }

    public record AllocationInput(Long invoiceId, BigDecimal amount) {
    }

    private record ApplyKey(long paymentId, List<AllocationInput> allocations) {
    }

    private record ReverseKey(long paymentId, String reason) {
    }

    private record Planned(long invoiceId, BigDecimal amount) {
    }

    public record SupplierRefView(long id, String name) {
    }

    public record AllocationView(long id, long invoiceId, String invoiceNumber, BigDecimal amount, Instant createdAt) {
    }

    public record PaymentDetail(long id, SupplierRefView supplier, String method, BigDecimal amount, String reference,
            Long cashSessionId, Instant paidAt, Long reverses, Long reversedBy, String reversalReason,
            List<AllocationView> allocations, BigDecimal unallocated, BigDecimal supplierBalance) {
    }

    public record PaymentSummary(long id, long supplierId, String method, BigDecimal amount, String reference,
            Instant paidAt, Long reverses, boolean reversed) {
    }

    public record OpenInvoice(long id, String invoiceNumber, LocalDate invoiceDate, LocalDate dueDate,
            BigDecimal totalTtc, BigDecimal open) {
    }

    public record OpenCreditNote(long id, String invoiceNumber, BigDecimal totalTtc, BigDecimal unapplied) {
    }

    public record Advance(long paymentId, Instant paidAt, BigDecimal unallocated) {
    }

    /** BR-PAY-014: {@code balance} = recorded invoices − recorded credit notes − payments in force (what we owe). */
    public record Payables(SupplierRefView supplier, BigDecimal balance, List<OpenInvoice> invoices,
            List<OpenCreditNote> creditNotes, List<Advance> advances) {
    }

    private final SupplierPaymentRepository payments;
    private final SupplierPaymentAllocationRepository allocations;
    private final SupplierInvoiceRepository invoices;
    private final SupplierInvoiceLocks invoiceLocks;
    private final SupplierOpenAmounts openAmounts;
    private final SupplierAccounts suppliers;
    private final CashLedger ledger;
    private final IdempotencyKeys keys;
    private final ActorProvider actors;
    private final AuditRecorder audit;
    private final Clock clock;

    public SupplierPayments(SupplierPaymentRepository payments, SupplierPaymentAllocationRepository allocations,
            SupplierInvoiceRepository invoices, SupplierInvoiceLocks invoiceLocks, SupplierOpenAmounts openAmounts,
            SupplierAccounts suppliers, CashLedger ledger, IdempotencyKeys keys, ActorProvider actors,
            AuditRecorder audit, Clock clock) {
        this.payments = payments;
        this.allocations = allocations;
        this.invoices = invoices;
        this.invoiceLocks = invoiceLocks;
        this.openAmounts = openAmounts;
        this.suppliers = suppliers;
        this.ledger = ledger;
        this.keys = keys;
        this.actors = actors;
        this.audit = audit;
        this.clock = clock;
    }

    /** BR-PAY-001, BR-PAY-009, BR-PAY-010. {@code idempotencyKey}: the header, {@code null} when absent. */
    @PreAuthorize("hasAuthority('payment.supplier.create')")
    @Transactional
    public PaymentDetail record(PaymentInput in, String idempotencyKey) {
        if (in.supplierId() == null) {
            throw invalid("supplierId", "REQUIRED", "The supplier is required.");
        }
        Tender tender = new Tender(in.method(), in.amount(), in.reference());
        PaymentMethod method = Tenders.validate(tender, "", PAYMENT_INVALID);
        requireWellFormed(in.allocations());
        long actor = actors.current().userId();
        Claim claim = keys.claim(actor, RECORD_ENDPOINT, idempotencyKey, in, "supplier_payment");
        if (claim.replayed().isPresent()) {
            return detail(claim.replayed().get());
        }
        SupplierRef supplier = suppliers.lock(in.supplierId())
                .orElseThrow(() -> invalid("supplierId", "UNKNOWN", "Unknown supplier."));
        if (!supplier.active()) {
            throw new BusinessException(ErrorCode.SUPPLIER_INACTIVE, "This supplier is inactive.",
                    Map.of("supplierId", supplier.id()));
        }
        BigDecimal amount = in.amount().setScale(Tenders.MONEY_SCALE);
        List<Planned> planned = plan(supplier.id(), amount, in.allocations());
        Long session = null;
        if (method == PaymentMethod.CASH) {
            session = ledger.lockCurrentSession();
            ledger.requireAvailable(session, amount);
        }
        Instant now = clock.instant();
        SupplierPayment payment = payments.saveAndFlush(new SupplierPayment(supplier.id(), method, amount,
                Tenders.reference(tender), session, actor, now));
        planned.forEach(p -> allocations.saveAndFlush(new SupplierPaymentAllocation(payment.id(), p.invoiceId(),
                p.amount(), actor, now)));
        if (session != null) {
            ledger.postSupplierPayment(session, CashDirection.OUT, amount, "supplier payment " + payment.id(),
                    payment.id());
        }
        claim.complete(payment.id());
        return detail(payment.id());
    }

    /** ADR-132 item 2: the unallocated part of a payment applied to invoices later. */
    @PreAuthorize("hasAuthority('payment.supplier.create')")
    @Transactional
    public PaymentDetail apply(long paymentId, List<AllocationInput> requested, String idempotencyKey) {
        requireWellFormed(requested);
        Claim claim = keys.claim(actors.current().userId(), APPLY_ENDPOINT, idempotencyKey,
                new ApplyKey(paymentId, requested), "supplier_payment");
        if (claim.replayed().isPresent()) {
            return detail(claim.replayed().get());
        }
        SupplierPayment payment = payments.findById(paymentId).orElseThrow(() -> notFound(paymentId));
        suppliers.lock(payment.supplierId());
        requireInForce(payment, "allocate");
        BigDecimal unallocated = payment.amount().subtract(allocations.allocatedFrom(paymentId));
        List<Planned> planned = plan(payment.supplierId(), unallocated, requested);
        long actor = actors.current().userId();
        Instant now = clock.instant();
        planned.forEach(p -> allocations.saveAndFlush(new SupplierPaymentAllocation(paymentId, p.invoiceId(),
                p.amount(), actor, now)));
        claim.complete(paymentId);
        return detail(paymentId);
    }

    /**
     * ADR-135, BR-PAY-013: once, with its reason; its allocations stop counting and its invoices reopen; cash comes back
     * into the reverser's own open session. Audited {@code PAYMENT_REVERSE}. Returns the reversed payment.
     */
    @PreAuthorize("hasAuthority('payment.reverse')")
    @Transactional
    public PaymentDetail reverse(long paymentId, String reason, String idempotencyKey) {
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) {
            throw new ValidationException("The reversal is invalid.",
                    List.of(new Violation("reason", "REQUIRED", "A reversal needs its reason.")));
        }
        if (why.length() > REASON_MAX) {
            throw new ValidationException("The reversal is invalid.",
                    List.of(new Violation("reason", "SIZE", "At most " + REASON_MAX + " characters.")));
        }
        long actor = actors.current().userId();
        Claim claim = keys.claim(actor, REVERSE_ENDPOINT, idempotencyKey, new ReverseKey(paymentId, why),
                "supplier_payment");
        if (claim.replayed().isPresent()) {
            return detail(claim.replayed().get());
        }
        SupplierPayment payment = payments.findById(paymentId).orElseThrow(() -> notFound(paymentId));
        suppliers.lock(payment.supplierId());
        requireInForce(payment, "reverse");
        Long session = payment.method() == PaymentMethod.CASH ? ledger.lockCurrentSession() : null;
        SupplierPayment reversal = payments.saveAndFlush(payment.reversal(why, actor, clock.instant(), session));
        if (session != null) {
            ledger.postSupplierPayment(session, CashDirection.IN, payment.amount(),
                    "reversal of supplier payment " + paymentId, reversal.id());
        }
        Map<String, Object> old = new LinkedHashMap<>();
        old.put("supplierId", payment.supplierId());
        old.put("method", payment.method().code());
        old.put("amount", payment.amount().toPlainString());
        old.put("cashSessionId", payment.cashSessionId());
        old.put("allocations", allocations.findByPaymentIdOrderByIdAsc(paymentId).stream().map(a -> Map.of(
                "invoiceId", a.invoiceId(), "amount", a.amount().toPlainString())).toList());
        Map<String, Object> now = new LinkedHashMap<>();
        now.put("reversalId", reversal.id());
        now.put("reason", why);
        now.put("cashSessionId", session);
        audit.record(AuditAction.PAYMENT_REVERSE, "supplier_payments", paymentId, old, now);
        claim.complete(paymentId);
        return detail(paymentId);
    }

    @PreAuthorize("hasAuthority('payment.read')")
    @Transactional(readOnly = true)
    public PaymentDetail get(long id) {
        return detail(id);
    }

    /** Newest first; {@code supplierId} optional. */
    @PreAuthorize("hasAuthority('payment.read')")
    @Transactional(readOnly = true)
    public PageResponse<PaymentSummary> search(Long supplierId, int page, int size) {
        var request = PageRequests.of(page, size, Sort.unsorted());
        var found = supplierId == null ? payments.findAllByOrderByIdDesc(request)
                : payments.findBySupplierIdOrderByIdDesc(supplierId, request);
        return PageResponse.of(found, p -> new PaymentSummary(p.id(), p.supplierId(), p.method().code(), p.amount(),
                p.reference(), p.paidAt(), p.reversesPaymentId(),
                p.reversesPaymentId() == null && payments.findByReversesPaymentId(p.id()).isPresent()));
    }

    /** BR-PAY-014: open invoices by due date, unapplied credit notes, advances, and the balance. */
    @PreAuthorize("hasAuthority('payment.read')")
    @Transactional(readOnly = true)
    public Payables payables(long supplierId) {
        SupplierRef supplier = suppliers.supplier(supplierId).orElseThrow(() -> new BusinessException(
                ErrorCode.NOT_FOUND, "Supplier not found.", Map.of("id", supplierId)));
        List<OpenInvoice> open = new ArrayList<>();
        BigDecimal invoiced = BigDecimal.ZERO;
        for (SupplierInvoice invoice : invoices.recordedOf(supplierId, false)) {
            invoiced = invoiced.add(invoice.totalTtc());
            BigDecimal left = openAmounts.open(invoice);
            if (left.signum() > 0) {
                open.add(new OpenInvoice(invoice.id(), invoice.invoiceNumber(), invoice.invoiceDate(),
                        invoice.dueDate(), invoice.totalTtc(), left));
            }
        }
        List<OpenCreditNote> notes = new ArrayList<>();
        BigDecimal credited = BigDecimal.ZERO;
        for (SupplierInvoice note : invoices.recordedOf(supplierId, true)) {
            credited = credited.add(note.totalTtc());
            BigDecimal left = openAmounts.open(note);
            if (left.signum() > 0) {
                notes.add(new OpenCreditNote(note.id(), note.invoiceNumber(), note.totalTtc(), left));
            }
        }
        List<Advance> advances = new ArrayList<>();
        for (SupplierPayment payment : payments.inForceOf(supplierId)) {
            BigDecimal left = payment.amount().subtract(allocations.allocatedFrom(payment.id()));
            if (left.signum() > 0) {
                advances.add(new Advance(payment.id(), payment.paidAt(), left));
            }
        }
        return new Payables(new SupplierRefView(supplier.id(), supplier.name()), balance(supplierId, invoiced,
                credited), open, notes, advances);
    }

    private BigDecimal balance(long supplierId, BigDecimal invoiced, BigDecimal credited) {
        return invoiced.subtract(credited).subtract(payments.paidInForce(supplierId)).setScale(Tenders.MONEY_SCALE);
    }

    /**
     * Locks the invoices concerned (only those of the supplier, re-checked after the lock) and decides what goes where:
     * without a request, the supplier's open invoices oldest due first until {@code available} is used; with one, each
     * line names a recorded invoice of the supplier and stays within its open amount, and the whole within
     * {@code available}.
     */
    private List<Planned> plan(long supplierId, BigDecimal available, List<AllocationInput> requested) {
        if (requested != null && requested.isEmpty()) {
            return List.of();
        }
        List<Long> ids = requested == null
                ? invoices.recordedOf(supplierId, false).stream().filter(i -> openAmounts.open(i).signum() > 0)
                        .map(SupplierInvoice::id).toList()
                : requested.stream().map(AllocationInput::invoiceId).toList();
        if (ids.isEmpty() || available.signum() <= 0 && requested == null) {
            return List.of();
        }
        Map<Long, SupplierInvoice> locked = new HashMap<>();
        invoices.findAllByIdIn(ids).stream().filter(i -> eligible(i, supplierId))
                .sorted(Comparator.comparing(SupplierInvoice::id)).forEach(i -> {
            invoiceLocks.lock(i);
            locked.put(i.id(), i);
        });
        List<Planned> planned = new ArrayList<>();
        if (requested == null) {
            BigDecimal left = available;
            List<SupplierInvoice> oldestFirst = locked.values().stream().filter(i -> eligible(i, supplierId))
                    .sorted(Comparator.comparing(SupplierInvoice::dueDate).thenComparing(SupplierInvoice::id)).toList();
            for (SupplierInvoice invoice : oldestFirst) {
                BigDecimal take = openAmounts.open(invoice).min(left);
                if (take.signum() > 0) {
                    planned.add(new Planned(invoice.id(), take));
                    left = left.subtract(take);
                }
            }
            return planned;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < requested.size(); i++) {
            AllocationInput line = requested.get(i);
            SupplierInvoice invoice = locked.get(line.invoiceId());
            if (invoice == null || !eligible(invoice, supplierId)) {
                throw invalid("allocations[" + i + "].invoiceId", "NOT_ALLOWED",
                        "Only a recorded invoice of this supplier can be settled.");
            }
            BigDecimal amount = line.amount().setScale(Tenders.MONEY_SCALE);
            BigDecimal open = openAmounts.open(invoice);
            if (amount.compareTo(open) > 0) {
                throw exceeded("This allocation exceeds the invoice's open amount.", Map.of("invoiceId",
                        invoice.id(), "open", open.toPlainString(), "requested", amount.toPlainString()));
            }
            planned.add(new Planned(invoice.id(), amount));
            total = total.add(amount);
        }
        if (total.compareTo(available) > 0) {
            throw exceeded("The allocations exceed what the payment has left.", Map.of("available",
                    available.setScale(Tenders.MONEY_SCALE).toPlainString(), "requested", total.toPlainString()));
        }
        return planned;
    }

    private static boolean eligible(SupplierInvoice invoice, long supplierId) {
        return invoice.recorded() && !invoice.creditNote() && invoice.supplierId() == supplierId;
    }

    /** Call it under the supplier's lock. */
    private void requireInForce(SupplierPayment payment, String action) {
        String refusal = payment.reversesPaymentId() != null ? "reversal"
                : payments.findByReversesPaymentId(payment.id()).isPresent() ? "reversed" : null;
        if (refusal != null) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "This payment is no longer in force.",
                    Map.of("paymentId", payment.id(), "action", action, "state", refusal));
        }
    }

    private static void requireWellFormed(List<AllocationInput> requested) {
        if (requested == null) {
            return;
        }
        Set<Long> seen = new HashSet<>();
        List<Violation> violations = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            AllocationInput line = requested.get(i);
            String prefix = "allocations[" + i + "].";
            if (line == null || line.invoiceId() == null) {
                violations.add(new Violation(prefix + "invoiceId", "REQUIRED", "The invoice is required."));
            } else if (!seen.add(line.invoiceId())) {
                violations.add(new Violation(prefix + "invoiceId", "DUPLICATE", "Each invoice at most once."));
            }
            BigDecimal amount = line == null ? null : line.amount();
            if (amount == null) {
                violations.add(new Violation(prefix + "amount", "REQUIRED", "The amount is required."));
            } else if (amount.signum() <= 0) {
                violations.add(new Violation(prefix + "amount", "POSITIVE", "The amount must be greater than zero."));
            } else if (amount.stripTrailingZeros().scale() > Tenders.MONEY_SCALE) {
                violations.add(new Violation(prefix + "amount", "SCALE", "At most two decimals."));
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(PAYMENT_INVALID, violations);
        }
    }

    private PaymentDetail detail(long id) {
        SupplierPayment payment = payments.findById(id).orElseThrow(() -> notFound(id));
        SupplierRef supplier = suppliers.supplier(payment.supplierId()).orElseThrow();
        List<SupplierPaymentAllocation> made = allocations.findByPaymentIdOrderByIdAsc(id);
        Map<Long, String> numbers = new HashMap<>();
        if (!made.isEmpty()) {
            invoices.findAllByIdIn(made.stream().map(SupplierPaymentAllocation::invoiceId).toList())
                    .forEach(i -> numbers.put(i.id(), i.invoiceNumber()));
        }
        BigDecimal allocated = made.stream().map(SupplierPaymentAllocation::amount).reduce(BigDecimal.ZERO,
                BigDecimal::add);
        Long reversedBy = payments.findByReversesPaymentId(id).map(SupplierPayment::id).orElse(null);
        BigDecimal unallocated = payment.reversesPaymentId() == null && reversedBy == null
                ? payment.amount().subtract(allocated) : BigDecimal.ZERO.setScale(Tenders.MONEY_SCALE);
        BigDecimal invoiced = invoices.recordedOf(supplier.id(), false).stream().map(SupplierInvoice::totalTtc)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credited = invoices.recordedOf(supplier.id(), true).stream().map(SupplierInvoice::totalTtc)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PaymentDetail(payment.id(), new SupplierRefView(supplier.id(), supplier.name()),
                payment.method().code(), payment.amount(), payment.reference(), payment.cashSessionId(),
                payment.paidAt(), payment.reversesPaymentId(), reversedBy, payment.reversalReason(),
                made.stream().map(a -> new AllocationView(a.id(), a.invoiceId(), numbers.get(a.invoiceId()),
                        a.amount(), a.createdAt())).toList(), unallocated, balance(supplier.id(), invoiced, credited));
    }

    private static BusinessException exceeded(String message, Map<String, Object> context) {
        return new BusinessException(ErrorCode.PAYMENT_ALLOCATION_EXCEEDED, message, context);
    }

    private static BusinessException notFound(long id) {
        return new BusinessException(ErrorCode.NOT_FOUND, "Payment not found.", Map.of("id", id));
    }

    private static ValidationException invalid(String field, String code, String message) {
        return new ValidationException(PAYMENT_INVALID, List.of(new Violation(field, code, message)));
    }
}
