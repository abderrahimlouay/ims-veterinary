package com.imsvet.ims.sales.application;

import com.imsvet.ims.common.security.InternalService;
import com.imsvet.ims.sales.domain.SalesDocument;
import com.imsvet.ims.sales.infrastructure.SalesDocumentRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    private final SalesDocumentRepository documents;

    public CreditDocuments(SalesDocumentRepository documents) {
        this.documents = documents;
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
