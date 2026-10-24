package com.imsvet.ims.inventory.application;

import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.security.InternalService;
import com.imsvet.ims.inventory.domain.ProposalStatus;
import com.imsvet.ims.inventory.domain.ReorderProposal;
import com.imsvet.ims.inventory.infrastructure.ProposalLocks;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reorder proposals as {@code purchasing} converts them into draft orders (BR-PURCH-010, ADR-102): the pending
 * proposals to convert, then, once the orders exist, each marked converted with its supplier, under lock and checked
 * again. Not found → {@code NOT_FOUND}; not pending → {@code INVALID_STATE_TRANSITION}. Callers check permissions.
 */
@Service
@InternalService
public class ProposalConversions {

    /** A pending proposal: its product and the suggested base quantity. */
    public record PendingProposal(long id, long productId, BigDecimal suggestedQty) {
    }

    private final ProposalLocks locks;
    private final Clock clock;

    public ProposalConversions(ProposalLocks locks, Clock clock) {
        this.locks = locks;
        this.clock = clock;
    }

    /** Locks the proposals (ascending id), all of which must exist and be pending; returned in the order given. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<PendingProposal> lockPending(List<Long> ids) {
        Map<Long, ReorderProposal> locked = locks.lockAll(ids).stream()
                .collect(Collectors.toMap(ReorderProposal::id, Function.identity()));
        return ids.stream().map(id -> {
            ReorderProposal p = locked.get(id);
            if (p == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "Reorder proposal not found.", Map.of("id", id));
            }
            if (p.status() != ProposalStatus.PENDING) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "This proposal is no longer pending.",
                        Map.of("id", id, "status", p.status().code(), "action", "convert"));
            }
            return new PendingProposal(p.id(), p.productId(), p.suggestedQty());
        }).toList();
    }

    /** Marks proposals locked by {@link #lockPending} as converted for the supplier. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markConverted(Collection<Long> ids, long supplierId) {
        locks.lockAll(ids).forEach(p -> p.convert(supplierId, clock.instant()));
        locks.flush();
    }
}
