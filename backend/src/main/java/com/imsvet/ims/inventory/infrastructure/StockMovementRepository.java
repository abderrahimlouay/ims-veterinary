package com.imsvet.ims.inventory.infrastructure;

import com.imsvet.ims.inventory.domain.StockMovement;
import org.springframework.data.repository.Repository;

/** Writes the ledger; used by {@code StockService} only (architecture test, ADR-088). Insert only: no update or delete. */
public interface StockMovementRepository extends Repository<StockMovement, Long> {

    StockMovement save(StockMovement movement);

    void flush();
}
