package com.imsvet.ims.purchasing.infrastructure;

import com.imsvet.ims.purchasing.domain.PurchaseOrder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

/**
 * Locks an order already read in the transaction ({@code SELECT … FOR UPDATE}) and reloads it with its lines, so a
 * receipt sees the received quantities as committed by any receipt that finished in between (ADR-012).
 */
@Repository
public class OrderLocks {

    private final EntityManager entityManager;

    OrderLocks(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public void lockAndRefresh(PurchaseOrder order) {
        entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE);
    }
}
