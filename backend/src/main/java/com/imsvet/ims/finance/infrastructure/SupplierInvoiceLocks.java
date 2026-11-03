package com.imsvet.ims.finance.infrastructure;

import com.imsvet.ims.finance.domain.SupplierInvoice;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

/**
 * Locks supplier invoice rows ({@code SELECT … FOR UPDATE}) and reloads them, in the order given (the caller sorts by
 * ascending id): after the supplier row in the lock order of database.md §7 (ADR-132, ADR-133).
 */
@Repository
public class SupplierInvoiceLocks {

    private final EntityManager entityManager;

    SupplierInvoiceLocks(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public void lock(SupplierInvoice invoice) {
        entityManager.flush();
        entityManager.refresh(invoice, LockModeType.PESSIMISTIC_WRITE);
    }
}
