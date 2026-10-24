package com.imsvet.ims.inventory.infrastructure;

import com.imsvet.ims.inventory.domain.ReorderProposal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * Locks reorder proposals ({@code SELECT … FOR UPDATE}) in ascending id and reloads them, so a conversion sees the
 * status committed by a conversion or dismissal that finished in between (as {@link LotLocks}).
 */
@Repository
public class ProposalLocks {

    private final EntityManager entityManager;

    ProposalLocks(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** The proposals that exist, in ascending id; unknown ids are left out. */
    public List<ReorderProposal> lockAll(Collection<Long> ids) {
        entityManager.flush();
        List<ReorderProposal> locked = new ArrayList<>();
        for (Long id : ids.stream().distinct().sorted().toList()) {
            ReorderProposal proposal = entityManager.find(ReorderProposal.class, id);
            if (proposal != null) {
                entityManager.refresh(proposal, LockModeType.PESSIMISTIC_WRITE);
                locked.add(proposal);
            }
        }
        return locked;
    }

    public void flush() {
        entityManager.flush();
    }
}
