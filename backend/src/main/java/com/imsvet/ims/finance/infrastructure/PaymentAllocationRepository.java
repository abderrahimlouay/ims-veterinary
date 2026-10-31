package com.imsvet.ims.finance.infrastructure;

import com.imsvet.ims.finance.domain.PaymentAllocation;
import org.springframework.data.repository.Repository;

/** Insert only (BR-PAY-003): no update or delete is exposed. */
public interface PaymentAllocationRepository extends Repository<PaymentAllocation, PaymentAllocation.Key> {

    PaymentAllocation saveAndFlush(PaymentAllocation allocation);
}
