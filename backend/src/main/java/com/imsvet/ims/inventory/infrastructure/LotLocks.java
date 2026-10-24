package com.imsvet.ims.inventory.infrastructure;

import com.imsvet.ims.inventory.domain.Lot;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Locks lots ({@code SELECT … FOR UPDATE}) in ascending id (ADR-012) and reloads them. A locking query returns a lot
 * already read in the transaction as it was read, so a movement committed in between would be lost; refreshing
 * under the lock always gives the committed balance and cost (BR-STOCK-001/002). Pending changes are flushed first,
 * so nothing of this transaction is discarded. A repository, so a lock wait timeout becomes a concurrency failure.
 */
@Repository
public class LotLocks {

    private final EntityManager entityManager;

    LotLocks(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Optional<Lot> lock(long id) {
        List<Lot> locked = lockAll(List.of(id));
        return locked.isEmpty() ? Optional.empty() : Optional.of(locked.getFirst());
    }

    /** The lots that exist, in ascending id; unknown ids are left out. */
    public List<Lot> lockAll(Collection<Long> ids) {
        entityManager.flush();
        List<Lot> locked = new ArrayList<>();
        for (Long id : ids.stream().distinct().sorted().toList()) {
            Lot lot = entityManager.find(Lot.class, id);
            if (lot != null) {
                entityManager.refresh(lot, LockModeType.PESSIMISTIC_WRITE);
                locked.add(lot);
            }
        }
        return locked;
    }
}
