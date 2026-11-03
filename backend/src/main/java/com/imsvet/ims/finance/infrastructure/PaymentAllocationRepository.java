package com.imsvet.ims.finance.infrastructure;

import com.imsvet.ims.finance.domain.PaymentAllocation;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Insert only (BR-PAY-003): no update or delete is exposed. */
public interface PaymentAllocationRepository extends Repository<PaymentAllocation, Long> {

    PaymentAllocation saveAndFlush(PaymentAllocation allocation);

    List<PaymentAllocation> findByPaymentIdOrderByIdAsc(Long paymentId);

    List<PaymentAllocation> findByPaymentIdInOrderByIdAsc(Collection<Long> paymentIds);

    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM PaymentAllocation a WHERE a.paymentId = :paymentId")
    BigDecimal allocatedFrom(long paymentId);

    /**
     * Per document among {@code invoiceIds}: what payments still in force allocate to it, as {@code [invoiceId, total]}
     * (a reversed payment's allocations stop counting, ADR-135).
     */
    @Query(value = """
            SELECT a.invoice_id, SUM(a.amount) FROM payment_allocations a
            WHERE a.invoice_id IN (:invoiceIds)
              AND NOT EXISTS (SELECT 1 FROM customer_payments r WHERE r.reverses_payment_id = a.payment_id)
            GROUP BY a.invoice_id""", nativeQuery = true)
    List<Object[]> allocatedTo(Collection<Long> invoiceIds);
}
