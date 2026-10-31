package com.imsvet.ims.sales.infrastructure;

import com.imsvet.ims.sales.domain.SalesDocument;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

/**
 * Locks a sales document row ({@code SELECT … FOR UPDATE}) and reloads it, so that a second confirmation of the same
 * document waits and then sees it confirmed (BR-SALE-016). After the idempotency key in the lock order of database.md §7.
 */
@Repository
public class SalesDocumentLocks {

    private final EntityManager entityManager;

    SalesDocumentLocks(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public void lockAndRefresh(SalesDocument document) {
        entityManager.refresh(document, LockModeType.PESSIMISTIC_WRITE);
    }
}
