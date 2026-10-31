package com.imsvet.ims.inventory.application;

import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.security.InternalService;
import com.imsvet.ims.inventory.domain.AlertLevel;
import com.imsvet.ims.inventory.domain.ExpiryPolicy;
import com.imsvet.ims.inventory.domain.FefoAllocator;
import com.imsvet.ims.inventory.domain.FefoAllocator.Allocation;
import com.imsvet.ims.inventory.domain.FefoAllocator.Candidate;
import com.imsvet.ims.inventory.domain.Lot;
import com.imsvet.ims.inventory.infrastructure.LotLocks;
import com.imsvet.ims.inventory.infrastructure.LotRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Port for the sales lines of Phase 7: the FEFO proposal for a quantity (BR-LOT-005), net of what the same draft
 * already holds, and the sellability of a chosen lot (BR-LOT-003/004). Informational: nothing is reserved or locked,
 * the stock is validated again under lock at confirmation. The caller checks its own permission. Joins its transaction.
 */
@Service
@InternalService
public class SaleLots {

    public record LotPick(long lotId, BigDecimal quantityBase) {
    }

    public record Proposal(List<LotPick> picks, BigDecimal shortfallBase) {
    }

    /** {@code alertLevel} {@code null} when the lot is not near expiry (ADR-056). */
    public record LotInfo(long id, String lotNumber, LocalDate expiryDate, String alertLevel) {
    }

    private final LotRepository lots;
    private final LotLocks locks;
    private final LotGuard guard;
    private final ExpiryPolicies policies;

    public SaleLots(LotRepository lots, LotLocks locks, LotGuard guard, ExpiryPolicies policies) {
        this.lots = lots;
        this.locks = locks;
        this.guard = guard;
        this.policies = policies;
    }

    /** {@code held}: base quantities per lot already on the draft. */
    @Transactional(readOnly = true)
    public Proposal propose(long productId, BigDecimal requestedBase, Map<Long, BigDecimal> held) {
        Allocation allocation = FefoAllocator.allocate(lots.findByProductIdInFefoOrder(productId).stream()
                .map(l -> new Candidate(l.id(), l.expiryDate(), available(l, held), l.status())).toList(),
                requestedBase, policies.current());
        return new Proposal(allocation.lines().stream()
                .map(line -> new LotPick(line.candidate().id(), line.quantityBase())).toList(),
                allocation.shortfallBase());
    }

    /**
     * Refuses a lot of another product and an expired, blocked or recalled one, whatever the user's rights
     * (ADR-120); a lot without enough left for {@code requestedBase} is {@code STOCK_INSUFFICIENT}.
     */
    @Transactional(readOnly = true)
    public void requireSellable(long lotId, long productId, BigDecimal requestedBase, Map<Long, BigDecimal> held) {
        Lot lot = guard.lotOfProduct(lotId, productId);
        ExpiryPolicy policy = policies.current();
        refuseUnsellable(lot, policy);
        if (available(lot, held).compareTo(requestedBase) < 0) {
            throw new BusinessException(ErrorCode.STOCK_INSUFFICIENT, "Not enough stock in this lot.",
                    Map.of("lotId", lotId, "available", available(lot, held).toPlainString()));
        }
    }

    private static void refuseUnsellable(Lot lot, ExpiryPolicy policy) {
        Map<String, Object> context = Map.of("lotId", lot.id());
        switch (policy.effectiveStatus(lot.status(), lot.expiryDate(), lot.quantityOnHand())) {
            case EXPIRED -> throw new BusinessException(ErrorCode.LOT_EXPIRED, "This lot has expired.", context);
            case BLOCKED -> throw new BusinessException(ErrorCode.LOT_BLOCKED, "This lot is blocked.", context);
            case RECALLED -> throw new BusinessException(ErrorCode.LOT_RECALLED, "This lot is recalled.", context);
            case ACTIVE, DEPLETED -> {
            }
        }
    }

    /**
     * Locks the lots in ascending id and validates them again (BR-LOT-003/004, BR-STOCK-012); returns each lot's cost
     * for the snapshot (BR-SALE-009). Stock itself is checked by {@link StockService} under the same locks.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, BigDecimal> lockForSale(Collection<Long> lotIds) {
        ExpiryPolicy policy = policies.current();
        Map<Long, BigDecimal> costs = new HashMap<>();
        for (Lot lot : locks.lockAll(lotIds.stream().distinct().sorted().toList())) {
            refuseUnsellable(lot, policy);
            costs.put(lot.id(), lot.unitCost());
        }
        return costs;
    }

    @Transactional(readOnly = true)
    public LotInfo info(long lotId) {
        Lot lot = lots.findById(lotId).orElseThrow();
        AlertLevel level = policies.current().level(lot.expiryDate());
        return new LotInfo(lot.id(), lot.lotNumber(), lot.expiryDate(), level == null ? null : level.code());
    }

    /** What can still be sold of a product (BR-STOCK-011). */
    @Transactional(readOnly = true)
    public BigDecimal sellable(long productId) {
        ExpiryPolicy policy = policies.current();
        return lots.findByProductIdInFefoOrder(productId).stream()
                .filter(l -> policy.sellable(l.status(), l.expiryDate(), l.quantityOnHand()))
                .map(Lot::quantityOnHand).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal available(Lot lot, Map<Long, BigDecimal> held) {
        return lot.quantityOnHand().subtract(held.getOrDefault(lot.id(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
    }
}
