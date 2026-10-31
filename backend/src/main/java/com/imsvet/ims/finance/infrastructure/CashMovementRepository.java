package com.imsvet.ims.finance.infrastructure;

import com.imsvet.ims.finance.domain.CashMovement;
import org.springframework.data.repository.Repository;

/** Insert only (BR-PAY-003): no update or delete is exposed. */
public interface CashMovementRepository extends Repository<CashMovement, Long> {

    CashMovement saveAndFlush(CashMovement movement);
}
