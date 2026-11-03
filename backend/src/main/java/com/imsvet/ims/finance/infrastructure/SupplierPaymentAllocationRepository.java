package com.imsvet.ims.finance.infrastructure;

import com.imsvet.ims.finance.domain.SupplierPaymentAllocation;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Insert only (BR-PAY-003): no update or delete is exposed. */
public interface SupplierPaymentAllocationRepository extends Repository<SupplierPaymentAllocation, Long> {

    SupplierPaymentAllocation saveAndFlush(SupplierPaymentAllocation allocation);

    List<SupplierPaymentAllocation> findByPaymentIdOrderByIdAsc(Long paymentId);

    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM SupplierPaymentAllocation a WHERE a.paymentId = :paymentId")
    BigDecimal allocatedFrom(long paymentId);

    /** What payments in force allocate to the invoice (ADR-135: a reversed payment's allocations stop counting). */
    @Query("""
            SELECT COALESCE(SUM(a.amount), 0) FROM SupplierPaymentAllocation a WHERE a.invoiceId = :invoiceId
              AND NOT EXISTS (SELECT 1 FROM SupplierPayment r WHERE r.reversesPaymentId = a.paymentId)""")
    BigDecimal paidOn(long invoiceId);

    /** The allocations in force on the invoice, oldest first. */
    @Query("""
            SELECT a FROM SupplierPaymentAllocation a WHERE a.invoiceId = :invoiceId
              AND NOT EXISTS (SELECT 1 FROM SupplierPayment r WHERE r.reversesPaymentId = a.paymentId)
            ORDER BY a.id""")
    List<SupplierPaymentAllocation> inForceOn(long invoiceId);
}
