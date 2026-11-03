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
import com.imsvet.ims.finance.domain.SupplierCreditAllocation;
import com.imsvet.ims.finance.domain.SupplierInvoice;
import com.imsvet.ims.finance.domain.SupplierPaymentAllocation;
import com.imsvet.ims.finance.infrastructure.SupplierCreditAllocationRepository;
import com.imsvet.ims.finance.infrastructure.SupplierInvoiceLocks;
import com.imsvet.ims.finance.infrastructure.SupplierInvoiceRepository;
import com.imsvet.ims.finance.infrastructure.SupplierPaymentAllocationRepository;
import com.imsvet.ims.purchasing.application.SupplierAccounts;
import com.imsvet.ims.purchasing.application.SupplierAccounts.ReceiptRef;
import com.imsvet.ims.purchasing.application.SupplierAccounts.ReturnRef;
import com.imsvet.ims.purchasing.application.SupplierAccounts.SupplierRef;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supplier invoices and credit notes (ADR-133, W1c, BR-PAY-008/011/015): recorded as the supplier issued them, never
 * edited; a credit note lowers what is owed and may be applied to invoices of the same supplier; a wrong entry is
 * cancelled with its reason while nothing settles it (a credit-note application, or a payment in force). Lock order (database.md §7): idempotency key, the supplier, the
 * supplier invoices in ascending id order.
 */
@Service
public class SupplierInvoices {

    private static final String APPLY_ENDPOINT = "POST /api/supplier-invoices/{id}/applications";
    private static final String INVALID = "The supplier invoice is invalid.";
    private static final int MONEY_SCALE = 2;
    private static final int NUMBER_MAX = 60;
    private static final int REASON_MAX = 500;

    public record InvoiceInput(Long supplierId, String invoiceNumber, Long receiptId, Long supplierReturnId,
            LocalDate invoiceDate, LocalDate dueDate, BigDecimal totalTtc, Boolean creditNote) {
    }

    public record ApplicationInput(Long invoiceId, BigDecimal amount) {
    }

    private record ApplyKey(long creditNoteId, List<ApplicationInput> allocations) {
    }

    public record Ref(long id, String name) {
    }

    public record DocumentRef(long id, String number) {
    }

    /** {@code otherId} is the invoice a credit note settles, or the credit note settling an invoice. */
    public record ApplicationView(long id, long otherId, String otherNumber, BigDecimal amount, Instant createdAt) {
    }

    /** An allocation of a supplier payment in force on this invoice. */
    public record PaymentView(long id, long paymentId, BigDecimal amount, Instant createdAt) {
    }

    public record Warning(String code, Map<String, Object> context) {
    }

    /** {@code open}: what is left to settle on an invoice, or to apply from a credit note; 0 once cancelled. */
    public record SupplierInvoiceDetail(long id, Ref supplier, String invoiceNumber, boolean creditNote,
            DocumentRef receipt, DocumentRef supplierReturn, LocalDate invoiceDate, LocalDate dueDate,
            BigDecimal totalTtc, BigDecimal open, String status, List<ApplicationView> applications,
            List<PaymentView> payments, String cancelReason, Long cancelledBy, Instant cancelledAt, Long createdBy, Instant createdAt,
            List<Warning> warnings) {
    }

    public record SupplierInvoiceSummary(long id, long supplierId, String supplierName, String invoiceNumber,
            boolean creditNote, LocalDate invoiceDate, LocalDate dueDate, BigDecimal totalTtc, BigDecimal open,
            String status) {
    }

    private final SupplierInvoiceRepository invoices;
    private final SupplierCreditAllocationRepository applications;
    private final SupplierPaymentAllocationRepository payments;
    private final SupplierInvoiceLocks locks;
    private final SupplierOpenAmounts openAmounts;
    private final SupplierAccounts suppliers;
    private final IdempotencyKeys keys;
    private final ActorProvider actors;
    private final AuditRecorder audit;
    private final Clock clock;

    public SupplierInvoices(SupplierInvoiceRepository invoices, SupplierCreditAllocationRepository applications,
            SupplierPaymentAllocationRepository payments, SupplierInvoiceLocks locks, SupplierOpenAmounts openAmounts,
            SupplierAccounts suppliers, IdempotencyKeys keys, ActorProvider actors,
            AuditRecorder audit, Clock clock) {
        this.invoices = invoices;
        this.applications = applications;
        this.payments = payments;
        this.locks = locks;
        this.openAmounts = openAmounts;
        this.suppliers = suppliers;
        this.keys = keys;
        this.actors = actors;
        this.audit = audit;
        this.clock = clock;
    }

    /** BR-PAY-008, BR-PAY-011, ADR-133 items 1 and 2. */
    @PreAuthorize("hasAuthority('supplierinvoice.manage')")
    @Transactional
    public SupplierInvoiceDetail record(InvoiceInput in) {
        boolean creditNote = Boolean.TRUE.equals(in.creditNote());
        String number = in.invoiceNumber() == null ? "" : in.invoiceNumber().trim();
        List<Violation> violations = new ArrayList<>();
        if (in.supplierId() == null) {
            violations.add(new Violation("supplierId", "REQUIRED", "The supplier is required."));
        }
        if (number.isEmpty()) {
            violations.add(new Violation("invoiceNumber", "REQUIRED", "The supplier's number is required."));
        } else if (number.length() > NUMBER_MAX) {
            violations.add(new Violation("invoiceNumber", "SIZE", "At most " + NUMBER_MAX + " characters."));
        }
        if (in.invoiceDate() == null) {
            violations.add(new Violation("invoiceDate", "REQUIRED", "The invoice date is required."));
        }
        if (in.totalTtc() == null) {
            violations.add(new Violation("totalTtc", "REQUIRED", "The total is required."));
        } else if (in.totalTtc().signum() < 0) {
            violations.add(new Violation("totalTtc", "NOT_NEGATIVE", "The total cannot be negative."));
        } else if (in.totalTtc().stripTrailingZeros().scale() > MONEY_SCALE) {
            violations.add(new Violation("totalTtc", "SCALE", "At most two decimals."));
        }
        if (in.supplierReturnId() != null && !creditNote) {
            violations.add(new Violation("supplierReturnId", "NOT_ALLOWED", "Only a credit note settles a return."));
        }
        throwIf(violations);
        SupplierRef supplier = suppliers.supplier(in.supplierId())
                .orElseThrow(() -> invalid("supplierId", "UNKNOWN", "Unknown supplier."));
        if (!supplier.active()) {
            throw new BusinessException(ErrorCode.SUPPLIER_INACTIVE, "This supplier is inactive.",
                    Map.of("supplierId", supplier.id()));
        }
        ReceiptRef receipt = null;
        if (in.receiptId() != null) {
            receipt = suppliers.receipt(in.receiptId())
                    .orElseThrow(() -> invalid("receiptId", "UNKNOWN", "Unknown receipt."));
            if (receipt.supplierId() != supplier.id()) {
                throw invalid("receiptId", "OTHER_SUPPLIER", "This receipt is from another supplier.");
            }
            if (!receipt.confirmed()) {
                throw invalid("receiptId", "NOT_CONFIRMED", "This receipt was cancelled.");
            }
        }
        if (in.supplierReturnId() != null) {
            ReturnRef returned = suppliers.supplierReturn(in.supplierReturnId())
                    .orElseThrow(() -> invalid("supplierReturnId", "UNKNOWN", "Unknown supplier return."));
            if (returned.supplierId() != supplier.id()) {
                throw invalid("supplierReturnId", "OTHER_SUPPLIER", "This return is to another supplier.");
            }
        }
        if (invoices.existsBySupplierIdAndInvoiceNumber(supplier.id(), number)) {
            throw duplicate(supplier.id(), number);
        }
        LocalDate due = in.dueDate() != null ? in.dueDate() : in.invoiceDate().plusDays(supplier.paymentTermsDays());
        BigDecimal total = in.totalTtc().setScale(MONEY_SCALE);
        SupplierInvoice saved;
        try {
            saved = invoices.saveAndFlush(new SupplierInvoice(supplier.id(), number, in.receiptId(),
                    in.supplierReturnId(), in.invoiceDate(), due, total, creditNote, actors.current().userId(),
                    clock.instant()));
        } catch (DataIntegrityViolationException e) {
            throw duplicate(supplier.id(), number);
        }
        List<Warning> warnings = new ArrayList<>();
        if (receipt != null && !creditNote && receipt.valueTtc().compareTo(total) != 0) {
            warnings.add(new Warning("RECEIPT_TOTAL_DIFFERS", Map.of("receiptId", receipt.id(), "receiptValue",
                    receipt.valueTtc().toPlainString(), "totalTtc", total.toPlainString())));
        }
        return detail(saved, warnings);
    }

    /** ADR-133 item 3: a credit note applied to invoices of its supplier, within what each has left and its own. */
    @PreAuthorize("hasAuthority('supplierinvoice.manage')")
    @Transactional
    public SupplierInvoiceDetail apply(long creditNoteId, List<ApplicationInput> requested, String idempotencyKey) {
        if (requested == null || requested.isEmpty()) {
            throw invalid("allocations", "REQUIRED", "At least one invoice to settle.");
        }
        List<Violation> violations = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < requested.size(); i++) {
            ApplicationInput line = requested.get(i);
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
            } else if (amount.stripTrailingZeros().scale() > MONEY_SCALE) {
                violations.add(new Violation(prefix + "amount", "SCALE", "At most two decimals."));
            }
        }
        throwIf(violations);
        long actor = actors.current().userId();
        Claim claim = keys.claim(actor, APPLY_ENDPOINT, idempotencyKey, new ApplyKey(creditNoteId, requested),
                "supplier_invoice");
        if (claim.replayed().isPresent()) {
            return get(claim.replayed().get());
        }
        SupplierInvoice creditNote = invoices.findById(creditNoteId).orElseThrow(() -> notFound(creditNoteId));
        suppliers.lock(creditNote.supplierId());
        List<Long> ids = new ArrayList<>(seen);
        ids.add(creditNoteId);
        Map<Long, SupplierInvoice> locked = new HashMap<>();
        invoices.findAllByIdIn(ids).stream().sorted(Comparator.comparing(SupplierInvoice::id)).forEach(i -> {
            locks.lock(i);
            locked.put(i.id(), i);
        });
        if (!creditNote.creditNote() || !creditNote.recorded()) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "Only a recorded credit note is applied.",
                    Map.of("id", creditNoteId, "action", "apply", "status", creditNote.status()));
        }
        BigDecimal available = open(creditNote);
        BigDecimal total = BigDecimal.ZERO;
        List<SupplierCreditAllocation> made = new ArrayList<>();
        Instant now = clock.instant();
        for (int i = 0; i < requested.size(); i++) {
            ApplicationInput line = requested.get(i);
            SupplierInvoice invoice = locked.get(line.invoiceId());
            if (invoice == null || invoice.creditNote() || !invoice.recorded()
                    || !invoice.supplierId().equals(creditNote.supplierId())) {
                throw invalid("allocations[" + i + "].invoiceId", "NOT_ALLOWED",
                        "Only a recorded invoice of the same supplier can be settled.");
            }
            BigDecimal amount = line.amount().setScale(MONEY_SCALE);
            BigDecimal left = open(invoice);
            if (amount.compareTo(left) > 0) {
                throw exceeded("This application exceeds the invoice's open amount.", Map.of("invoiceId",
                        invoice.id(), "open", left.toPlainString(), "requested", amount.toPlainString()));
            }
            total = total.add(amount);
            made.add(new SupplierCreditAllocation(creditNoteId, invoice.id(), amount, actor, now));
        }
        if (total.compareTo(available) > 0) {
            throw exceeded("The applications exceed what the credit note has left.", Map.of("available",
                    available.toPlainString(), "requested", total.toPlainString()));
        }
        made.forEach(applications::saveAndFlush);
        claim.complete(creditNoteId);
        return get(creditNoteId);
    }

    /** BR-PAY-015: refused while anything settles the document; audited {@code SUPPLIER_INVOICE_CANCEL}. */
    @PreAuthorize("hasAuthority('supplierinvoice.manage')")
    @Transactional
    public SupplierInvoiceDetail cancel(long id, String reason) {
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) {
            throw invalid("reason", "REQUIRED", "A cancellation needs its reason.");
        }
        if (why.length() > REASON_MAX) {
            throw invalid("reason", "SIZE", "At most " + REASON_MAX + " characters.");
        }
        SupplierInvoice invoice = invoices.findById(id).orElseThrow(() -> notFound(id));
        suppliers.lock(invoice.supplierId());
        locks.lock(invoice);
        if (!invoice.recorded()) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "This document is already cancelled.",
                    Map.of("id", id, "action", "cancel", "status", invoice.status()));
        }
        if (!invoice.creditNote()) {
            List<SupplierPaymentAllocation> paid = payments.inForceOn(id);
            if (!paid.isEmpty()) {
                throw new BusinessException(ErrorCode.INVOICE_HAS_SETTLEMENTS,
                        "A payment settles this invoice; reverse the payment first.", Map.of("invoiceId", id,
                                "blockedBy", "payment", "paymentId", paid.getFirst().paymentId()));
            }
        }
        List<SupplierCreditAllocation> settling = applications.findByInvoiceIdOrCreditNoteIdOrderByIdAsc(id, id);
        if (!settling.isEmpty()) {
            SupplierCreditAllocation first = settling.getFirst();
            throw new BusinessException(ErrorCode.INVOICE_HAS_SETTLEMENTS,
                    "Something settles this document; it cannot be cancelled.", Map.of("invoiceId", id, "blockedBy",
                            "credit_note", "documentId", first.invoiceId().equals(id) ? first.creditNoteId()
                                    : first.invoiceId()));
        }
        Map<String, Object> old = new LinkedHashMap<>();
        old.put("status", invoice.status());
        old.put("supplierId", invoice.supplierId());
        old.put("invoiceNumber", invoice.invoiceNumber());
        old.put("creditNote", invoice.creditNote());
        old.put("totalTtc", invoice.totalTtc().toPlainString());
        invoice.cancel(why, actors.current().userId(), clock.instant());
        audit.record(AuditAction.SUPPLIER_INVOICE_CANCEL, "supplier_invoices", id, old,
                Map.of("status", SupplierInvoice.CANCELLED, "reason", why));
        return detail(invoices.saveAndFlush(invoice), List.of());
    }

    @PreAuthorize("hasAuthority('supplierinvoice.manage')")
    @Transactional(readOnly = true)
    public SupplierInvoiceDetail get(long id) {
        return detail(invoices.findById(id).orElseThrow(() -> notFound(id)), List.of());
    }

    /** Every filter optional; {@code openOnly}: recorded documents with something left. By due date. */
    @PreAuthorize("hasAuthority('supplierinvoice.manage')")
    @Transactional(readOnly = true)
    public PageResponse<SupplierInvoiceSummary> search(Long supplierId, Boolean creditNote, LocalDate dueBefore,
            boolean openOnly, int page, int size) {
        Map<Long, String> names = new HashMap<>();
        return PageResponse.of(invoices.search(supplierId, creditNote, dueBefore, openOnly,
                PageRequests.of(page, size, Sort.unsorted())), i -> new SupplierInvoiceSummary(i.id(), i.supplierId(),
                        names.computeIfAbsent(i.supplierId(), s -> suppliers.supplier(s).map(SupplierRef::name)
                                .orElse(null)), i.invoiceNumber(), i.creditNote(), i.invoiceDate(), i.dueDate(),
                        i.totalTtc(), open(i), i.status()));
    }

    private BigDecimal open(SupplierInvoice invoice) {
        return openAmounts.open(invoice);
    }

    private SupplierInvoiceDetail detail(SupplierInvoice invoice, List<Warning> warnings) {
        SupplierRef supplier = suppliers.supplier(invoice.supplierId()).orElseThrow();
        DocumentRef receipt = invoice.receiptId() == null ? null : suppliers.receipt(invoice.receiptId())
                .map(r -> new DocumentRef(r.id(), r.number())).orElse(null);
        DocumentRef returned = invoice.supplierReturnId() == null ? null : suppliers.supplierReturn(
                invoice.supplierReturnId()).map(r -> new DocumentRef(r.id(), r.number())).orElse(null);
        List<SupplierCreditAllocation> made = applications.findByInvoiceIdOrCreditNoteIdOrderByIdAsc(invoice.id(),
                invoice.id());
        Map<Long, String> numbers = new HashMap<>();
        invoices.findAllByIdIn(made.stream().map(a -> a.invoiceId().equals(invoice.id()) ? a.creditNoteId()
                : a.invoiceId()).toList()).forEach(i -> numbers.put(i.id(), i.invoiceNumber()));
        List<ApplicationView> views = made.stream().map(a -> {
            long other = a.invoiceId().equals(invoice.id()) ? a.creditNoteId() : a.invoiceId();
            return new ApplicationView(a.id(), other, numbers.get(other), a.amount(), a.createdAt());
        }).toList();
        return new SupplierInvoiceDetail(invoice.id(), new Ref(supplier.id(), supplier.name()),
                invoice.invoiceNumber(), invoice.creditNote(), receipt, returned, invoice.invoiceDate(),
                invoice.dueDate(), invoice.totalTtc(), open(invoice), invoice.status(), views,
                payments.inForceOn(invoice.id()).stream().map(a -> new PaymentView(a.id(), a.paymentId(), a.amount(),
                        a.createdAt())).toList(), invoice.cancelReason(),
                invoice.cancelledBy(), invoice.cancelledAt(), invoice.createdBy(), invoice.createdAt(), warnings);
    }

    private static void throwIf(List<Violation> violations) {
        if (!violations.isEmpty()) {
            throw new ValidationException(INVALID, violations);
        }
    }

    private static BusinessException duplicate(long supplierId, String number) {
        return new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "This supplier invoice number is already recorded.",
                Map.of("supplierId", supplierId, "invoiceNumber", number));
    }

    private static BusinessException exceeded(String message, Map<String, Object> context) {
        return new BusinessException(ErrorCode.PAYMENT_ALLOCATION_EXCEEDED, message, context);
    }

    private static BusinessException notFound(long id) {
        return new BusinessException(ErrorCode.NOT_FOUND, "Supplier invoice not found.", Map.of("id", id));
    }

    private static ValidationException invalid(String field, String code, String message) {
        return new ValidationException(INVALID, List.of(new Violation(field, code, message)));
    }
}
