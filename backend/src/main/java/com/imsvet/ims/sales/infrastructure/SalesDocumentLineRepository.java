package com.imsvet.ims.sales.infrastructure;

import com.imsvet.ims.sales.domain.SalesDocumentLine;
import com.imsvet.ims.sales.domain.SalesDocType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface SalesDocumentLineRepository extends Repository<SalesDocumentLine, Long> {

    interface LotSaleRow {
        Long getCustomerId();

        String getCustomerName();

        String getPhone();

        String getDocumentNumber();

        SalesDocType getDocumentType();

        Instant getIssuedAt();

        BigDecimal getQuantityBase();
    }

    SalesDocumentLine saveAndFlush(SalesDocumentLine line);

    /** One row per document that posted stock of the lot, however many of its lines used it. */
    @Query("""
            SELECT d.customerId AS customerId, c.name AS customerName, c.phone AS phone, d.number AS documentNumber,
                   d.docType AS documentType, d.issuedAt AS issuedAt, SUM(l.quantityBase) AS quantityBase
            FROM SalesDocumentLine l
            JOIN SalesDocument d ON d.id = l.documentId
            LEFT JOIN Customer c ON c.id = d.customerId
            WHERE l.lotId = :lotId
              AND d.status = com.imsvet.ims.sales.domain.SalesDocStatus.CONFIRMED
              AND d.docType IN (com.imsvet.ims.sales.domain.SalesDocType.DELIVERY_NOTE,
                                com.imsvet.ims.sales.domain.SalesDocType.RECEIPT,
                                com.imsvet.ims.sales.domain.SalesDocType.INVOICE)
              AND NOT EXISTS (SELECT 1 FROM SalesDocument p WHERE p.id = d.parentId
                              AND d.docType = com.imsvet.ims.sales.domain.SalesDocType.INVOICE
                              AND p.docType = com.imsvet.ims.sales.domain.SalesDocType.DELIVERY_NOTE)
            GROUP BY d.id, d.customerId, c.name, c.phone, d.number, d.docType, d.issuedAt
            ORDER BY d.issuedAt, d.id""")
    List<LotSaleRow> salesOfLot(long lotId);

    List<SalesDocumentLine> findByDocumentIdOrderByIdAsc(Long documentId);

    Optional<SalesDocumentLine> findByIdAndDocumentId(Long id, Long documentId);

    void delete(SalesDocumentLine line);

    void flush();
}
