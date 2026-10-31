package com.imsvet.ims.sales.infrastructure;

import com.imsvet.ims.sales.domain.SalesDocStatus;
import com.imsvet.ims.sales.domain.SalesDocType;
import com.imsvet.ims.sales.domain.SalesDocument;
import java.time.Instant;
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
}
