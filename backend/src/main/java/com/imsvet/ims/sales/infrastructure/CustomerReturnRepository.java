package com.imsvet.ims.sales.infrastructure;

import com.imsvet.ims.sales.domain.CustomerReturn;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Insert and read only (BR-RET-007): no update or delete is exposed. */
public interface CustomerReturnRepository extends Repository<CustomerReturn, Long> {

    CustomerReturn saveAndFlush(CustomerReturn customerReturn);

    Optional<CustomerReturn> findById(Long id);

    /**
     * Every filter optional; {@code customerId} is the original document's customer; {@code from} inclusive,
     * {@code to} exclusive; newest first.
     */
    @Query("""
            SELECT r FROM CustomerReturn r
            WHERE (:documentId IS NULL OR r.documentId = :documentId)
              AND (:customerId IS NULL OR r.documentId IN
                   (SELECT d.id FROM SalesDocument d WHERE d.customerId = :customerId))
              AND (:from IS NULL OR r.createdAt >= :from)
              AND (:to IS NULL OR r.createdAt < :to)
            ORDER BY r.createdAt DESC, r.id DESC""")
    Page<CustomerReturn> search(Long customerId, Long documentId, Instant from, Instant to, Pageable pageable);

    /** A document's returns, oldest first. */
    List<CustomerReturn> findByDocumentIdOrderByIdAsc(Long documentId);
}
