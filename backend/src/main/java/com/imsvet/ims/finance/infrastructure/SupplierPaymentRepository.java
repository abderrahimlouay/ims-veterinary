package com.imsvet.ims.finance.infrastructure;

import com.imsvet.ims.finance.domain.SupplierPayment;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Insert only (BR-PAY-003): no update or delete is exposed. */
public interface SupplierPaymentRepository extends Repository<SupplierPayment, Long> {

    SupplierPayment saveAndFlush(SupplierPayment payment);

    Optional<SupplierPayment> findById(Long id);

    Optional<SupplierPayment> findByReversesPaymentId(Long paymentId);

    Page<SupplierPayment> findBySupplierIdOrderByIdDesc(Long supplierId, Pageable pageable);

    Page<SupplierPayment> findAllByOrderByIdDesc(Pageable pageable);

    /** Payments of the supplier in force: neither a reversal nor reversed (ADR-135). */
    @Query("""
            SELECT p FROM SupplierPayment p WHERE p.supplierId = :supplierId AND p.reversesPaymentId IS NULL
              AND NOT EXISTS (SELECT 1 FROM SupplierPayment r WHERE r.reversesPaymentId = p.id)
            ORDER BY p.paidAt, p.id""")
    List<SupplierPayment> inForceOf(long supplierId);

    @Query("""
            SELECT COALESCE(SUM(p.amount), 0) FROM SupplierPayment p WHERE p.supplierId = :supplierId
              AND p.reversesPaymentId IS NULL
              AND NOT EXISTS (SELECT 1 FROM SupplierPayment r WHERE r.reversesPaymentId = p.id)""")
    BigDecimal paidInForce(long supplierId);
}
