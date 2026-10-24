package com.imsvet.ims.purchasing.infrastructure;

import com.imsvet.ims.purchasing.domain.PurchaseOrderLine;
import org.springframework.data.repository.Repository;

/** Read access to order lines outside their order (BR-UNIT-004). */
public interface PurchaseOrderLineRepository extends Repository<PurchaseOrderLine, Long> {

    boolean existsByUnitId(Long unitId);
}
