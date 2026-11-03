package com.imsvet.ims.purchasing.infrastructure;

import com.imsvet.ims.purchasing.domain.Supplier;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Locks a supplier row ({@code SELECT … FOR UPDATE}) and reloads it: what serialises the settlements of that supplier
 * (applications of credit notes and advances, reversals, cancellations), since a payment row cannot be locked
 * (append-only, ADR-132). First after the idempotency key in the lock order of database.md §7.
 */
@Repository
public class SupplierLocks {

    private final EntityManager entityManager;

    SupplierLocks(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Optional<Supplier> lock(long id) {
        entityManager.flush();
        Supplier supplier = entityManager.find(Supplier.class, id);
        if (supplier == null) {
            return Optional.empty();
        }
        entityManager.refresh(supplier, LockModeType.PESSIMISTIC_WRITE);
        return Optional.of(supplier);
    }
}
