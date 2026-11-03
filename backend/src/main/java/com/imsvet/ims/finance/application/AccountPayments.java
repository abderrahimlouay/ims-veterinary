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
import com.imsvet.ims.finance.application.AccountPaymentViews.Advance;
import com.imsvet.ims.finance.application.AccountPaymentViews.AllocationView;
import com.imsvet.ims.finance.application.AccountPaymentViews.CustomerRef;
import com.imsvet.ims.finance.application.AccountPaymentViews.OpenInvoice;
import com.imsvet.ims.finance.application.AccountPaymentViews.OpenItems;
import com.imsvet.ims.finance.application.AccountPaymentViews.PaymentDetail;
import com.imsvet.ims.finance.application.AccountPaymentViews.PaymentSummary;
import com.imsvet.ims.finance.domain.CashDirection;
import com.imsvet.ims.finance.domain.CustomerPayment;
import com.imsvet.ims.finance.domain.PaymentAllocation;
import com.imsvet.ims.finance.domain.PaymentMethod;
import com.imsvet.ims.finance.infrastructure.CustomerPaymentRepository;
import com.imsvet.ims.finance.infrastructure.PaymentAllocationRepository;
import com.imsvet.ims.sales.application.CreditDocuments;
import com.imsvet.ims.sales.application.CreditDocuments.AccountHolder;
import com.imsvet.ims.sales.application.CreditDocuments.CreditInvoice;
import com.imsvet.ims.sales.application.PaymentRecorder.Tender;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
 * Payments on account (ADR-134, W8): a customer pays, the payment is allocated to confirmed credit invoices (as asked,
 * or oldest first) and what is left is an advance, applicable later by new allocation rows. Nothing is ever changed or
 * removed (BR-PAY-003). Lock order (database.md §7): idempotency key, the customer (when applying: a payment row
 * cannot be locked, being append-only), the invoices in ascending id order, then the cash session.
 */
@Service
public class AccountPayments {

    private static final String RECORD_ENDPOINT = "POST /api/payments/customers";
    private static final String APPLY_ENDPOINT = "POST /api/payments/customers/{id}/allocations";
    private static final String REVERSE_ENDPOINT = "POST /api/payments/customers/{id}/reverse";
    private static final int REASON_MAX = 500;
    private static final String PAYMENT_INVALID = "The payment is invalid.";
    private static final String ALLOCATIONS = "allocations";
    private static final Comparator<CreditInvoice> OLDEST_FIRST = Comparator
            .comparing(CreditInvoice::dueDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(CreditInvoice::number, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(CreditInvoice::id);

    /** {@code allocations}: {@code null} = oldest first; empty = all of it an advance. */
    public record PaymentInput(Long customerId, String method, BigDecimal amount, String reference,
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

    private final CustomerPaymentRepository payments;
    private final PaymentAllocationRepository allocations;
    private final CreditDocuments documents;
    private final CreditAccounts accounts;
    private final CashLedger ledger;
    private final IdempotencyKeys keys;
    private final ActorProvider actors;
    private final AuditRecorder audit;
    private final Clock clock;

    public AccountPayments(CustomerPaymentRepository payments, PaymentAllocationRepository allocations,
            CreditDocuments documents, CreditAccounts accounts, CashLedger ledger,
            IdempotencyKeys keys, ActorProvider actors, AuditRecorder audit, Clock clock) {
        this.payments = payments;
        this.allocations = allocations;
        this.documents = documents;
        this.accounts = accounts;
        this.ledger = ledger;
        this.keys = keys;
        this.actors = actors;
        this.audit = audit;
        this.clock = clock;
    }

    /** BR-PAY-001, BR-PAY-002, BR-PAY-006, BR-PAY-012. {@code idempotencyKey}: the header, {@code null} when absent. */
    @PreAuthorize("hasAuthority('payment.customer.create')")
    @Transactional
    public PaymentDetail record(PaymentInput in, String idempotencyKey) {
        if (in.customerId() == null) {
            throw invalid("customerId", "REQUIRED", "The customer is required.");
        }
        Tender tender = new Tender(in.method(), in.amount(), in.reference());
        PaymentMethod method = Tenders.validate(tender, "", PAYMENT_INVALID);
        requireWellFormed(in.allocations());
        long actor = actors.current().userId();
        Claim claim = keys.claim(actor, RECORD_ENDPOINT, idempotencyKey, in, "customer_payment");
        if (claim.replayed().isPresent()) {
            return detail(claim.replayed().get());
        }
        AccountHolder holder = documents.holder(in.customerId());
        if (!holder.active()) {
            throw new BusinessException(ErrorCode.CUSTOMER_INACTIVE, "This customer is inactive.",
                    Map.of("customerId", holder.id()));
        }
        BigDecimal amount = in.amount().setScale(Tenders.MONEY_SCALE);
        List<Planned> planned = plan(holder.id(), amount, in.allocations());
        Long session = method == PaymentMethod.CASH ? ledger.lockCurrentSession() : null;
        Instant now = clock.instant();
        CustomerPayment payment = payments.saveAndFlush(new CustomerPayment(holder.id(), method, amount,
                Tenders.reference(tender), session, actor, now));
        planned.forEach(p -> allocations.saveAndFlush(new PaymentAllocation(payment.id(), p.invoiceId(), p.amount(),
                actor, now)));
        if (session != null) {
            ledger.post(session, CashDirection.IN, PaymentMethod.CASH, amount, "payment on account " + payment.id(),
                    payment.id());
        }
        claim.complete(payment.id());
        return detail(payment.id());
    }

    /** ADR-134 item 3: the unallocated part of a payment on account, applied later; BR-PAY-002, BR-PAY-010. */
    @PreAuthorize("hasAuthority('payment.customer.create')")
    @Transactional
    public PaymentDetail apply(long paymentId, List<AllocationInput> requested, String idempotencyKey) {
        requireWellFormed(requested);
        Claim claim = keys.claim(actors.current().userId(), APPLY_ENDPOINT, idempotencyKey,
                new ApplyKey(paymentId, requested), "customer_payment");
        if (claim.replayed().isPresent()) {
            return detail(claim.replayed().get());
        }
        CustomerPayment payment = payments.findById(paymentId).orElseThrow(() -> notFound(paymentId));
        if (payment.customerId() != null) {
            documents.lockHolder(payment.customerId());
        }
        requireAccountPayment(payment, "allocate", "This payment has no advance to apply.");
        BigDecimal unallocated = payment.amount().subtract(allocations.allocatedFrom(paymentId));
        List<Planned> planned = plan(payment.customerId(), unallocated, requested);
        long actor = actors.current().userId();
        Instant now = clock.instant();
        planned.forEach(p -> allocations.saveAndFlush(new PaymentAllocation(paymentId, p.invoiceId(), p.amount(),
                actor, now)));
        claim.complete(paymentId);
        return detail(paymentId);
    }

    /**
     * ADR-135, BR-PAY-013: a payment on account is reversed once, by a new row with the reason; its allocations stop
     * counting, so its invoices reopen and the balance goes back up. Cash goes out of the reverser's own open session,
     * refused beyond its expected cash. Audited {@code PAYMENT_REVERSE}. Lock order: idempotency key, the customer, the
     * cash session. Returns the reversed payment, with {@code reversedBy}.
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
                "customer_payment");
        if (claim.replayed().isPresent()) {
            return detail(claim.replayed().get());
        }
        CustomerPayment payment = payments.findById(paymentId).orElseThrow(() -> notFound(paymentId));
        if (payment.customerId() != null) {
            documents.lockHolder(payment.customerId());
        }
        requireAccountPayment(payment, "reverse", "This payment cannot be reversed here.");
        Long session = null;
        if (payment.method() == PaymentMethod.CASH) {
            session = ledger.lockCurrentSession();
            ledger.requireAvailable(session, payment.amount());
        }
        CustomerPayment reversal = payments.saveAndFlush(payment.reversal(why, actor, clock.instant(), session));
        if (session != null) {
            ledger.post(session, CashDirection.OUT, PaymentMethod.CASH, payment.amount(),
                    "reversal of payment " + paymentId, reversal.id());
        }
        List<PaymentAllocation> made = allocations.findByPaymentIdOrderByIdAsc(paymentId);
        Map<String, Object> old = new LinkedHashMap<>();
        old.put("customerId", payment.customerId());
        old.put("method", payment.method().code());
        old.put("amount", payment.amount().toPlainString());
        old.put("cashSessionId", payment.cashSessionId());
        old.put("allocations", made.stream().map(a -> Map.of("invoiceId", a.invoiceId(), "amount",
                a.amount().toPlainString())).toList());
        Map<String, Object> now = new LinkedHashMap<>();
        now.put("reversalId", reversal.id());
        now.put("reason", why);
        now.put("cashSessionId", session);
        audit.record(AuditAction.PAYMENT_REVERSE, "customer_payments", paymentId, old, now);
        claim.complete(paymentId);
        return detail(paymentId);
    }

    @PreAuthorize("hasAuthority('payment.read')")
    @Transactional(readOnly = true)
    public PaymentDetail get(long id) {
        return detail(id);
    }

    /** Newest first; {@code customerId} optional. */
    @PreAuthorize("hasAuthority('payment.read')")
    @Transactional(readOnly = true)
    public PageResponse<PaymentSummary> search(Long customerId, int page, int size) {
        var request = PageRequests.of(page, size, Sort.unsorted());
        var found = customerId == null ? payments.findAllByOrderByIdDesc(request)
                : payments.findByCustomerIdOrderByIdDesc(customerId, request);
        return PageResponse.of(found, p -> new PaymentSummary(p.id(), p.customerId(), p.method().code(), p.amount(),
                p.reference(), p.paidAt(), p.reversesPaymentId(),
                p.reversesPaymentId() == null && payments.findByReversesPaymentId(p.id()).isPresent()));
    }

    /** The invoices a payment may settle, oldest first, and the advances that may settle them (api.md §4.9). */
    @PreAuthorize("hasAuthority('payment.read')")
    @Transactional(readOnly = true)
    public OpenItems openItems(long customerId) {
        documents.holder(customerId);
        List<CreditInvoice> invoices = documents.creditInvoices(customerId);
        Map<Long, BigDecimal> open = open(invoices);
        List<OpenInvoice> listed = invoices.stream().filter(i -> open.get(i.id()).signum() > 0)
                .map(i -> new OpenInvoice(i.id(), i.number(), i.issuedAt(), i.dueDate(), i.totalTtc(), open.get(i.id())))
                .toList();
        List<Advance> advances = payments.advances(customerId).stream().map(row -> {
            CustomerPayment payment = payments.findById(((Number) row[0]).longValue()).orElseThrow();
            return new Advance(payment.id(), payment.paidAt(), (BigDecimal) row[1]);
        }).toList();
        return new OpenItems(listed, advances);
    }

    /**
     * Locks the invoices concerned and decides what goes where. Without a request, the invoices still open are taken
     * oldest first (due date, then number) until {@code available} is used; with one, each line must name a confirmed
     * credit invoice of the customer and stay within its open amount, and the whole within {@code available}.
     */
    private List<Planned> plan(long customerId, BigDecimal available, List<AllocationInput> requested) {
        if (requested == null) {
            List<CreditInvoice> candidates = documents.creditInvoices(customerId);
            Map<Long, BigDecimal> before = open(candidates);
            List<Long> stillOpen = candidates.stream().filter(i -> before.get(i.id()).signum() > 0)
                    .map(CreditInvoice::id).toList();
            if (stillOpen.isEmpty() || available.signum() <= 0) {
                return List.of();
            }
            List<CreditInvoice> locked = new ArrayList<>(documents.lockCreditInvoices(customerId, stillOpen));
            locked.sort(OLDEST_FIRST);
            Map<Long, BigDecimal> open = open(locked);
            List<Planned> planned = new ArrayList<>();
            BigDecimal left = available;
            for (CreditInvoice invoice : locked) {
                BigDecimal take = open.get(invoice.id()).min(left);
                if (take.signum() > 0) {
                    planned.add(new Planned(invoice.id(), take));
                    left = left.subtract(take);
                }
            }
            return planned;
        }
        if (requested.isEmpty()) {
            return List.of();
        }
        List<Long> ids = requested.stream().map(AllocationInput::invoiceId).toList();
        List<CreditInvoice> locked = documents.lockCreditInvoices(customerId, ids);
        Map<Long, BigDecimal> open = open(locked);
        List<Planned> planned = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < requested.size(); i++) {
            AllocationInput line = requested.get(i);
            BigDecimal invoiceOpen = open.get(line.invoiceId());
            if (invoiceOpen == null) {
                throw invalid(ALLOCATIONS + "[" + i + "].invoiceId", "NOT_ALLOWED",
                        "Only a confirmed credit invoice of this customer can be settled.");
            }
            BigDecimal amount = line.amount().setScale(Tenders.MONEY_SCALE);
            if (amount.compareTo(invoiceOpen) > 0) {
                throw exceeded("This allocation exceeds the invoice's open amount.", Map.of("invoiceId",
                        line.invoiceId(), "open", invoiceOpen.toPlainString(), "requested", amount.toPlainString()));
            }
            planned.add(new Planned(line.invoiceId(), amount));
            total = total.add(amount);
        }
        if (total.compareTo(available) > 0) {
            throw exceeded("The allocations exceed what the payment has left.", Map.of("available",
                    available.toPlainString(), "requested", total.toPlainString()));
        }
        return planned;
    }

    /** Per invoice, never below zero: TTC − its credit notes − what payments in force allocate to it. */
    private Map<Long, BigDecimal> open(List<CreditInvoice> invoices) {
        Map<Long, BigDecimal> allocated = new HashMap<>();
        if (!invoices.isEmpty()) {
            for (Object[] row : allocations.allocatedTo(invoices.stream().map(CreditInvoice::id).toList())) {
                allocated.put(((Number) row[0]).longValue(), (BigDecimal) row[1]);
            }
        }
        Map<Long, BigDecimal> open = new LinkedHashMap<>();
        for (CreditInvoice invoice : invoices) {
            BigDecimal left = invoice.beforePayments().subtract(allocated.getOrDefault(invoice.id(), BigDecimal.ZERO));
            open.put(invoice.id(), left.signum() > 0 ? left.setScale(Tenders.MONEY_SCALE) : BigDecimal.ZERO
                    .setScale(Tenders.MONEY_SCALE));
        }
        return open;
    }

    /**
     * Only a payment on account still in force has an advance to apply or may be reversed here; a counter payment never
     * (ADR-134 item 5, ADR-135 item 4). Call it under the customer's lock.
     */
    private void requireAccountPayment(CustomerPayment payment, String action, String message) {
        String refusal = null;
        if (payment.customerId() == null || payment.reversesPaymentId() != null) {
            refusal = payment.customerId() == null ? "counter_payment" : "reversal";
        } else if (payments.findByReversesPaymentId(payment.id()).isPresent()) {
            refusal = "reversed";
        } else {
            Set<Long> credit = new HashSet<>(documents.creditDocumentIds(payment.customerId()));
            if (allocations.findByPaymentIdOrderByIdAsc(payment.id()).stream()
                    .anyMatch(a -> !credit.contains(a.invoiceId()))) {
                refusal = "counter_payment";
            }
        }
        if (refusal != null) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, message,
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
            String prefix = ALLOCATIONS + "[" + i + "].";
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
        CustomerPayment payment = payments.findById(id).orElseThrow(() -> notFound(id));
        List<PaymentAllocation> made = allocations.findByPaymentIdOrderByIdAsc(id);
        Map<Long, String> numbers = documents.numbers(made.stream().map(PaymentAllocation::invoiceId).toList());
        BigDecimal allocated = made.stream().map(PaymentAllocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        CustomerRef customer = null;
        BigDecimal balance = null;
        if (payment.customerId() != null) {
            AccountHolder holder = documents.holder(payment.customerId());
            customer = new CustomerRef(holder.id(), holder.name());
            balance = accounts.position(holder.id()).balance();
        }
        Long reversedBy = payments.findByReversesPaymentId(id).map(CustomerPayment::id).orElse(null);
        return new PaymentDetail(payment.id(), customer, payment.method().code(), payment.amount(), payment.reference(),
                payment.cashSessionId(), payment.paidAt(), payment.reversesPaymentId(), reversedBy,
                made.stream().map(a -> new AllocationView(a.id(), a.invoiceId(), numbers.get(a.invoiceId()), a.amount(),
                        a.createdAt())).toList(),
                payment.reversesPaymentId() == null && reversedBy == null ? payment.amount().subtract(allocated)
                        : BigDecimal.ZERO.setScale(Tenders.MONEY_SCALE),
                balance);
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
