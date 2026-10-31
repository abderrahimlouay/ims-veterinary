package com.imsvet.ims.finance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;

/** What a payment settles on a document (BR-PAY-002): never changed or removed (SD-102). */
@Entity
@Table(name = "payment_allocations")
public class PaymentAllocation {

    @Embeddable
    public record Key(@Column(name = "payment_id") Long paymentId, @Column(name = "invoice_id") Long invoiceId)
            implements Serializable {
    }

    @EmbeddedId
    private Key key;

    @Column(nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    protected PaymentAllocation() {
    }

    public PaymentAllocation(Long paymentId, Long invoiceId, BigDecimal amount) {
        this.key = new Key(paymentId, invoiceId);
        this.amount = amount;
    }
}
