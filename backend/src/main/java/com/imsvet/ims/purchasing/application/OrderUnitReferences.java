package com.imsvet.ims.purchasing.application;

import com.imsvet.ims.catalog.application.UnitReferences;
import com.imsvet.ims.purchasing.infrastructure.PurchaseOrderLineRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** BR-UNIT-004: a unit used on a purchase-order line keeps its factor (the catalog asks every module). */
@Component
class OrderUnitReferences implements UnitReferences {

    private final PurchaseOrderLineRepository lines;

    OrderUnitReferences(PurchaseOrderLineRepository lines) {
        this.lines = lines;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean referencesUnit(long unitId) {
        return lines.existsByUnitId(unitId);
    }
}
