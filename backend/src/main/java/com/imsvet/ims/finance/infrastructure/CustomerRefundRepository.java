package com.imsvet.ims.finance.infrastructure;

import com.imsvet.ims.finance.domain.CustomerRefund;
import java.util.List;
import org.springframework.data.repository.Repository;

/** Insert only (BR-PAY-003): no update or delete is exposed. */
public interface CustomerRefundRepository extends Repository<CustomerRefund, Long> {

    CustomerRefund saveAndFlush(CustomerRefund refund);

    List<CustomerRefund> findByCreditNoteIdOrderByIdAsc(Long creditNoteId);
}
