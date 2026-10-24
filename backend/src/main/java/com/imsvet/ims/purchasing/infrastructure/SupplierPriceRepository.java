package com.imsvet.ims.purchasing.infrastructure;

import com.imsvet.ims.purchasing.domain.SupplierPrice;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Price history: appended, never edited or deleted (BR-PURCH-008). */
public interface SupplierPriceRepository extends Repository<SupplierPrice, Long> {

    SupplierPrice saveAndFlush(SupplierPrice price);

    /** {@code productId} optional; newest first (valid date, then entry). */
    @Query("""
            SELECT p FROM SupplierPrice p
            WHERE p.supplierId = :supplierId AND (:productId IS NULL OR p.productId = :productId)
            ORDER BY p.validFrom DESC, p.id DESC""")
    Page<SupplierPrice> history(long supplierId, Long productId, Pageable pageable);

    /**
     * Entries in force on {@code day}, newest first, without those of a cancelled receipt (ADR-106): the first is the
     * "latest price" used by the prefill (ADR-099) and by a receipt to decide whether to append (ADR-100).
     */
    @Query("""
            SELECT p FROM SupplierPrice p
            WHERE p.supplierId = :supplierId AND p.productId = :productId AND p.validFrom <= :day
              AND p.cancelled = false
            ORDER BY p.validFrom DESC, p.id DESC""")
    List<SupplierPrice> inForce(long supplierId, long productId, LocalDate day, Pageable first);
}
