package com.imsvet.ims.sales.application;

import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.security.InternalService;
import com.imsvet.ims.sales.domain.Customer;
import com.imsvet.ims.sales.domain.SalesDocStatus;
import com.imsvet.ims.sales.domain.SalesDocType;
import com.imsvet.ims.sales.domain.SalesDocument;
import com.imsvet.ims.sales.infrastructure.CustomerLocks;
import com.imsvet.ims.sales.infrastructure.CustomerRepository;
import com.imsvet.ims.sales.infrastructure.SalesDocumentLocks;
import com.imsvet.ims.sales.infrastructure.SalesDocumentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read port through which {@code finance} sees a customer's credit documents (BR-PAY-004, ADR-117, ADR-118); only
 * confirmed documents count, never drafts. The caller checks its permission.
 */
@Service
@InternalService
public class CreditDocuments {

    public record CreditTotals(BigDecimal invoiced, BigDecimal creditNoted, BigDecimal uninvoicedDeliveryNotes) {
    }

    /** {@code open}: its total less its credit notes, before any payment. */
    public record DueInvoice(long id, String number, BigDecimal open) {
    }

    /** The customer a payment on account is for (ADR-134). */
    public record AccountHolder(long id, String name, boolean active) {
    }

    /** A confirmed credit invoice and the TTC of its confirmed credit notes, before any payment. */
    public record CreditInvoice(long id, String number, Instant issuedAt, LocalDate dueDate, BigDecimal totalTtc,
            BigDecimal creditNoted) {

        public BigDecimal beforePayments() {
            return totalTtc.subtract(creditNoted);
        }
    }

    private final SalesDocumentRepository documents;
    private final CustomerRepository customers;
    private final SalesDocumentLocks locks;
    private final CustomerLocks customerLocks;

    public CreditDocuments(SalesDocumentRepository documents, CustomerRepository customers, SalesDocumentLocks locks,
            CustomerLocks customerLocks) {
        this.documents = documents;
        this.customers = customers;
        this.locks = locks;
        this.customerLocks = customerLocks;
    }

    /**
     * Locks the customer row: what serialises the applications and reversals of that customer's payments, since a
     * payment row cannot be locked (append-only, SELECT and INSERT only; ADR-134). First after the idempotency key.
     */
    @Transactional
    public AccountHolder lockHolder(long customerId) {
        Customer customer = customerLocks.lock(customerId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "Customer not found.", Map.of("id", customerId)));
        return new AccountHolder(customer.id(), customer.name(), customer.active());
    }

    /** {@code NOT_FOUND} for an unknown customer. */
    @Transactional(readOnly = true)
    public AccountHolder holder(long customerId) {
        Customer customer = customers.findById(customerId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "Customer not found.", Map.of("id", customerId)));
        return new AccountHolder(customer.id(), customer.name(), customer.active());
    }

    /** The customer's confirmed credit invoices, oldest due first, then by number (ADR-134). */
    @Transactional(readOnly = true)
    public List<CreditInvoice> creditInvoices(long customerId) {
        Map<Long, BigDecimal> credited = creditNoted(customerId);
        return documents.creditInvoicesOf(customerId).stream()
                .map(d -> invoice(d, credited.getOrDefault(d.id(), BigDecimal.ZERO))).toList();
    }

    /**
     * Locks those of {@code ids} that are confirmed credit invoices of the customer, in ascending id order, reloaded
     * (database.md §7: after the customer when applying, before the cash session), and returns those still so after
     * the lock; the caller refuses the others. Nothing else is locked, so never a draft that a sale's credit check may
     * hold. A credit note made meanwhile is seen, since the return locks the same row.
     */
    @Transactional
    public List<CreditInvoice> lockCreditInvoices(long customerId, Collection<Long> ids) {
        List<SalesDocument> eligible = documents.findAllByIdIn(ids).stream().filter(d -> creditInvoiceOf(d, customerId))
                .sorted(Comparator.comparing(SalesDocument::id)).toList();
        eligible.forEach(locks::lockAndRefresh);
        Map<Long, BigDecimal> credited = creditNoted(customerId);
        return eligible.stream().filter(d -> creditInvoiceOf(d, customerId))
                .map(d -> invoice(d, credited.getOrDefault(d.id(), BigDecimal.ZERO))).toList();
    }

    private static boolean creditInvoiceOf(SalesDocument d, long customerId) {
        return d.customerId() != null && d.customerId() == customerId && d.creditSale()
                && d.docType() == SalesDocType.INVOICE && d.status() == SalesDocStatus.CONFIRMED;
    }

    /** Document numbers by id, for the views of payments; a draft has none. */
    @Transactional(readOnly = true)
    public Map<Long, String> numbers(Collection<Long> ids) {
        Map<Long, String> numbers = new HashMap<>();
        if (!ids.isEmpty()) {
            documents.findAllByIdIn(ids).forEach(d -> numbers.put(d.id(), d.number()));
        }
        return numbers;
    }

    private Map<Long, BigDecimal> creditNoted(long customerId) {
        Map<Long, BigDecimal> credited = new HashMap<>();
        for (Object[] row : documents.creditNotedPerInvoice(customerId)) {
            credited.put((Long) row[0], (BigDecimal) row[1]);
        }
        return credited;
    }

    private static CreditInvoice invoice(SalesDocument d, BigDecimal creditNoted) {
        return new CreditInvoice(d.id(), d.number(), d.issuedAt(), d.dueDate(), d.totalTtc(), creditNoted);
    }

    @Transactional(readOnly = true)
    public CreditTotals totals(long customerId) {
        return new CreditTotals(documents.creditInvoiced(customerId), documents.creditNoted(customerId),
                documents.uninvoicedCreditDeliveryNotes(customerId));
    }

    /** Every document bought on credit: a payment allocated to anything else was taken at the counter. */
    @Transactional(readOnly = true)
    public List<Long> creditDocumentIds(long customerId) {
        return documents.creditDocumentIds(customerId);
    }

    /** The confirmed credit invoices due before {@code date} that credit notes have not cleared. */
    @Transactional(readOnly = true)
    public List<DueInvoice> dueBefore(long customerId, LocalDate date) {
        return documents.creditInvoicesDueBefore(customerId, date).stream().map(this::due)
                .filter(i -> i.open().signum() > 0).toList();
    }

    private DueInvoice due(SalesDocument invoice) {
        return new DueInvoice(invoice.id(), invoice.number(),
                invoice.totalTtc().subtract(documents.creditNotedOn(invoice.id())));
    }
}
