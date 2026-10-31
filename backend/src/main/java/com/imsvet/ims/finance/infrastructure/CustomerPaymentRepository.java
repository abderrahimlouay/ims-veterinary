package com.imsvet.ims.finance.infrastructure;

import com.imsvet.ims.finance.domain.CustomerPayment;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Insert only (BR-PAY-003): no update or delete is exposed. */
public interface CustomerPaymentRepository extends Repository<CustomerPayment, Long> {

    CustomerPayment saveAndFlush(CustomerPayment payment);

    /** Allocated to the document and not reversed; a reversal has no allocation of its own. */
    @Query(value = """
            SELECT p.* FROM customer_payments p
            JOIN payment_allocations a ON a.payment_id = p.id
            WHERE a.invoice_id = :documentId
              AND NOT EXISTS (SELECT 1 FROM customer_payments r WHERE r.reverses_payment_id = p.id)
            ORDER BY p.id""", nativeQuery = true)
    List<CustomerPayment> inForceOn(long documentId);

    /**
     * What the customer paid on account and is still in force (ADR-118): allocated to a credit document or not allocated
     * at all, so never a counter payment. A reversal cancels its payment and counts neither way.
     */
    @Query(value = """
            SELECT COALESCE(SUM(p.amount), 0) FROM customer_payments p
            WHERE p.customer_id = :customerId AND p.reverses_payment_id IS NULL
              AND NOT EXISTS (SELECT 1 FROM customer_payments r WHERE r.reverses_payment_id = p.id)
              AND (NOT EXISTS (SELECT 1 FROM payment_allocations a WHERE a.payment_id = p.id)
                   OR EXISTS (SELECT 1 FROM payment_allocations a WHERE a.payment_id = p.id
                              AND a.invoice_id IN (:creditDocuments)))""", nativeQuery = true)
    BigDecimal accountPayments(long customerId, List<Long> creditDocuments);

    /** What is allocated to the document by payments still in force. */
    @Query(value = """
            SELECT COALESCE(SUM(a.amount), 0) FROM payment_allocations a
            JOIN customer_payments p ON p.id = a.payment_id
            WHERE a.invoice_id = :documentId
              AND NOT EXISTS (SELECT 1 FROM customer_payments r WHERE r.reverses_payment_id = p.id)""",
            nativeQuery = true)
    BigDecimal allocatedTo(long documentId);
}
