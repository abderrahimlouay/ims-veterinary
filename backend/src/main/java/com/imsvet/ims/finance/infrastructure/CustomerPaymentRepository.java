package com.imsvet.ims.finance.infrastructure;

import com.imsvet.ims.finance.domain.CustomerPayment;
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
}
