package com.imsvet.ims.purchasing.infrastructure;

import com.imsvet.ims.purchasing.domain.SupplierPrice;
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
}
