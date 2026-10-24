package com.imsvet.ims.purchasing.infrastructure;

import com.imsvet.ims.purchasing.domain.SupplierReturn;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Supplier returns are created once and never edited or deleted (ADR-101). */
public interface SupplierReturnRepository extends Repository<SupplierReturn, Long> {

    SupplierReturn saveAndFlush(SupplierReturn supplierReturn);

    void flush();

    Optional<SupplierReturn> findById(Long id);

    /** Every filter optional; {@code from} inclusive, {@code to} exclusive; newest first. */
    @Query("""
            SELECT r FROM SupplierReturn r
            WHERE (:supplierId IS NULL OR r.supplierId = :supplierId)
              AND (:from IS NULL OR r.createdAt >= :from)
              AND (:to IS NULL OR r.createdAt < :to)
            ORDER BY r.createdAt DESC, r.id DESC""")
    Page<SupplierReturn> search(Long supplierId, Instant from, Instant to, Pageable pageable);
}
