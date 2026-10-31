package com.imsvet.ims.sales.infrastructure;

import com.imsvet.ims.sales.domain.SalesDocStatus;
import com.imsvet.ims.sales.domain.SalesDocType;
import com.imsvet.ims.sales.domain.SalesDocument;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface SalesDocumentRepository extends Repository<SalesDocument, Long> {

    SalesDocument saveAndFlush(SalesDocument document);

    Optional<SalesDocument> findById(Long id);

    /** A draft has no issue date yet, so it is dated by its creation. */
    @Query("""
            SELECT d FROM SalesDocument d
            WHERE (:docType IS NULL OR d.docType = :docType)
              AND (:status IS NULL OR d.status = :status)
              AND (:customerId IS NULL OR d.customerId = :customerId)
              AND (:number IS NULL OR d.number = :number)
              AND (:from IS NULL OR COALESCE(d.issuedAt, d.createdAt) >= :from)
              AND (:to IS NULL OR COALESCE(d.issuedAt, d.createdAt) < :to)
            ORDER BY d.id DESC""")
    Page<SalesDocument> search(SalesDocType docType, SalesDocStatus status, Long customerId, String number,
            Instant from, Instant to, Pageable pageable);

    boolean existsByParentIdAndStatusNot(Long parentId, SalesDocStatus status);

    @Query("""
            SELECT COUNT(d) > 0 FROM SalesDocument d WHERE d.id = :id
              AND d.docType = com.imsvet.ims.sales.domain.SalesDocType.DELIVERY_NOTE""")
    boolean isDeliveryNote(long id);

    /** ADR-125: its lines, lots and prices are the delivery note's, and its goods have already left. */
    default boolean invoicesADeliveryNote(SalesDocument document) {
        return document.parentId() != null && isDeliveryNote(document.parentId());
    }

    @Query("""
            SELECT COALESCE(SUM(d.totalTtc), 0) FROM SalesDocument d
            WHERE d.customerId = :customerId AND d.creditSale = TRUE
              AND d.docType = com.imsvet.ims.sales.domain.SalesDocType.INVOICE
              AND d.status = com.imsvet.ims.sales.domain.SalesDocStatus.CONFIRMED""")
    BigDecimal creditInvoiced(long customerId);

    /** Credit notes of credit invoices only: the credit note of a sale paid at the counter never touches the balance. */
    @Query("""
            SELECT COALESCE(SUM(d.totalTtc), 0) FROM SalesDocument d
            WHERE d.customerId = :customerId
              AND d.docType = com.imsvet.ims.sales.domain.SalesDocType.CREDIT_NOTE
              AND d.status = com.imsvet.ims.sales.domain.SalesDocStatus.CONFIRMED
              AND d.parentId IN (SELECT p.id FROM SalesDocument p WHERE p.creditSale = TRUE
                  AND p.docType = com.imsvet.ims.sales.domain.SalesDocType.INVOICE)""")
    BigDecimal creditNoted(long customerId);

    /** A delivery note stops counting once a confirmed invoice, in the balance from then on, carries it (ADR-117, ADR-125). */
    @Query("""
            SELECT COALESCE(SUM(d.totalTtc), 0) FROM SalesDocument d
            WHERE d.customerId = :customerId AND d.creditSale = TRUE
              AND d.docType = com.imsvet.ims.sales.domain.SalesDocType.DELIVERY_NOTE
              AND d.status = com.imsvet.ims.sales.domain.SalesDocStatus.CONFIRMED
              AND NOT EXISTS (SELECT 1 FROM SalesDocument i WHERE i.parentId = d.id
                  AND i.docType = com.imsvet.ims.sales.domain.SalesDocType.INVOICE
                  AND i.status = com.imsvet.ims.sales.domain.SalesDocStatus.CONFIRMED)""")
    BigDecimal uninvoicedCreditDeliveryNotes(long customerId);

    @Query("SELECT d.id FROM SalesDocument d WHERE d.customerId = :customerId AND d.creditSale = TRUE")
    List<Long> creditDocumentIds(long customerId);

    @Query("""
            SELECT d FROM SalesDocument d
            WHERE d.customerId = :customerId AND d.creditSale = TRUE AND d.dueDate < :date
              AND d.docType = com.imsvet.ims.sales.domain.SalesDocType.INVOICE
              AND d.status = com.imsvet.ims.sales.domain.SalesDocStatus.CONFIRMED
            ORDER BY d.dueDate, d.id""")
    List<SalesDocument> creditInvoicesDueBefore(long customerId, LocalDate date);

    @Query("""
            SELECT COALESCE(SUM(d.totalTtc), 0) FROM SalesDocument d
            WHERE d.parentId = :invoiceId
              AND d.docType = com.imsvet.ims.sales.domain.SalesDocType.CREDIT_NOTE
              AND d.status = com.imsvet.ims.sales.domain.SalesDocStatus.CONFIRMED""")
    BigDecimal creditNotedOn(long invoiceId);
}
