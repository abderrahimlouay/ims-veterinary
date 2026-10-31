package com.imsvet.ims.finance.infrastructure;

import com.imsvet.ims.finance.domain.CashSession;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Locks a session row ({@code SELECT … FOR UPDATE}) and reloads it, so a posting and a closing never overlap: the
 * closing sums what was posted, and nothing posts after it (BR-CASH-004). Last in the lock order of database.md §7.
 */
@Repository
public class CashSessionLocks {

    private final EntityManager entityManager;

    CashSessionLocks(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Optional<CashSession> lock(long id) {
        entityManager.flush();
        CashSession session = entityManager.find(CashSession.class, id);
        if (session == null) {
            return Optional.empty();
        }
        entityManager.refresh(session, LockModeType.PESSIMISTIC_WRITE);
        return Optional.of(session);
    }
}
