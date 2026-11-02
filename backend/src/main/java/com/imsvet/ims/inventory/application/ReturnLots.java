package com.imsvet.ims.inventory.application;

import com.imsvet.ims.common.security.InternalService;
import com.imsvet.ims.inventory.domain.ExpiryPolicy;
import com.imsvet.ims.inventory.domain.Lot;
import com.imsvet.ims.inventory.infrastructure.LotLocks;
import com.imsvet.ims.inventory.domain.StockMovement;
import com.imsvet.ims.inventory.infrastructure.LotRepository;
import com.imsvet.ims.inventory.infrastructure.MovementReadRepository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Port for customer returns (W3, ADR-129): what a returned line's lot is — stored status and whether it is expired on
 * the business date (ADR-055) — read for the preview, or locked in ascending id (ADR-012) before the return decides
 * each disposition, so a status change cannot slip in between. The movements go through {@link StockService}, which
 * takes the same locks again. The caller checks its own permission.
 */
@Service
@InternalService
public class ReturnLots {

    /** {@code status}: the stored status, {@code active}, {@code blocked} or {@code recalled}. */
    public record ReturnLot(long id, long productId, String lotNumber, LocalDate expiryDate, String status,
            boolean expired) {
    }

    private final LotRepository lots;
    private final LotLocks locks;
    private final ExpiryPolicies policies;
    private final MovementReadRepository movements;

    public ReturnLots(LotRepository lots, LotLocks locks, ExpiryPolicies policies, MovementReadRepository movements) {
        this.lots = lots;
        this.locks = locks;
        this.policies = policies;
        this.movements = movements;
    }

    /** The ids of the movements each referenced line posted, oldest first. */
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> movementIds(String referenceType, Collection<Long> referenceIds) {
        return movements.findByReferenceTypeAndReferenceIdInOrderByIdAsc(referenceType, referenceIds).stream()
                .collect(Collectors.groupingBy(StockMovement::referenceId,
                        Collectors.mapping(StockMovement::id, Collectors.toList())));
    }

    @Transactional(readOnly = true)
    public Map<Long, ReturnLot> find(Collection<Long> ids) {
        return view(lots.findAllByIdIn(ids));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, ReturnLot> lock(Collection<Long> ids) {
        return view(locks.lockAll(ids.stream().distinct().sorted().toList()));
    }

    private Map<Long, ReturnLot> view(List<Lot> found) {
        ExpiryPolicy policy = policies.current();
        return found.stream().map(l -> new ReturnLot(l.id(), l.productId(), l.lotNumber(), l.expiryDate(),
                l.status().code(), policy.expired(l.expiryDate()))).collect(Collectors.toMap(ReturnLot::id,
                        Function.identity()));
    }
}
