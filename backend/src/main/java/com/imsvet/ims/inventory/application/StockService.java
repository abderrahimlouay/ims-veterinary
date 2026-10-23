package com.imsvet.ims.inventory.application;

import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.security.InternalService;
import com.imsvet.ims.inventory.domain.Lot;
import com.imsvet.ims.inventory.domain.MovementType;
import com.imsvet.ims.inventory.domain.StockMovement;
import com.imsvet.ims.inventory.infrastructure.LotRepository;
import com.imsvet.ims.inventory.infrastructure.StockMovementRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only writer of stock (BR-STOCK-002, ADR-088). Runs only inside the caller's use-case transaction (READ
 * COMMITTED, ADR-013): locks the lots {@code FOR UPDATE} in ascending id order (ADR-012), checks every movement —
 * lot of the product (BR-LOT-007), sign (BR-STOCK-005), reason (BR-STOCK-006), balance ≥ 0 (BR-STOCK-001) — before
 * applying any, then inserts the attributable movements (BR-STOCK-004) and moves the balances by the same amounts
 * (ADR-028). It does not touch the stored lot status (ADR-059). Callers check their own permission.
 */
@Service
@InternalService
public class StockService {

    /** One signed base-unit change of one lot; {@code reason} required for adjustments. */
    public record Movement(long lotId, long productId, MovementType type, BigDecimal quantity, String reason,
            String referenceType, Long referenceId) {
    }

    /** Base quantities keep 3 decimals (database.md §2.3). */
    private static final int QUANTITY_SCALE = 3;

    private final LotRepository lots;
    private final StockMovementRepository movements;
    private final ActorProvider actors;
    private final Clock clock;

    public StockService(LotRepository lots, StockMovementRepository movements, ActorProvider actors, Clock clock) {
        this.lots = lots;
        this.movements = movements;
        this.actors = actors;
        this.clock = clock;
    }

    /** All or nothing; returns the ids of the movements, in the order given. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> post(List<Movement> requests) {
        Long userId = actors.current().userId();
        if (userId == null) {
            throw new IllegalStateException("Every stock movement needs an operator (BR-STOCK-004)");
        }
        requests.forEach(StockService::checkShape);
        Map<Long, Lot> locked = lock(requests);
        Map<Long, BigDecimal> after = new HashMap<>();
        for (Movement m : requests) {
            Lot lot = locked.get(m.lotId());
            if (lot.productId() != m.productId()) {
                throw new BusinessException(ErrorCode.LOT_PRODUCT_MISMATCH, "This lot belongs to another product.",
                        Map.of("lotId", m.lotId(), "productId", m.productId()));
            }
            BigDecimal balance = after.getOrDefault(m.lotId(), lot.quantityOnHand()).add(m.quantity());
            if (balance.signum() < 0) {
                Map<String, Object> context = new LinkedHashMap<>();
                context.put("lotId", m.lotId());
                context.put("available", lot.quantityOnHand().setScale(QUANTITY_SCALE).toPlainString());
                context.put("requested", m.quantity().negate().setScale(QUANTITY_SCALE).toPlainString());
                throw new BusinessException(ErrorCode.STOCK_INSUFFICIENT, "Not enough stock on the lot.", context);
            }
            after.put(m.lotId(), balance);
        }
        Instant now = clock.instant();
        List<Long> ids = new ArrayList<>();
        for (Movement m : requests) {
            locked.get(m.lotId()).applyDelta(m.quantity());
            ids.add(movements.save(new StockMovement(m.lotId(), m.productId(), m.type(),
                    m.quantity().setScale(QUANTITY_SCALE), m.referenceType(), m.referenceId(), reason(m), userId, now))
                    .id());
        }
        try {
            movements.flush();
        } catch (DataIntegrityViolationException e) {
            BusinessException translated = StockConstraintTranslator.translate(e);
            throw translated != null ? translated : e;
        }
        return ids;
    }

    private Map<Long, Lot> lock(List<Movement> requests) {
        List<Long> ids = requests.stream().map(Movement::lotId).distinct().sorted().toList();
        Map<Long, Lot> locked = lots.findAllByIdInForUpdate(ids).stream()
                .collect(Collectors.toMap(Lot::id, Function.identity()));
        for (Long id : ids) {
            if (!locked.containsKey(id)) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "Lot not found.", Map.of("id", id));
            }
        }
        return locked;
    }

    private static void checkShape(Movement m) {
        if (!m.type().allows(m.quantity())) {
            throw new ValidationException("The movement is invalid.",
                    List.of(new Violation("quantity", "SIGN", "The quantity has the wrong sign for this movement type.")));
        }
        if (m.quantity().stripTrailingZeros().scale() > QUANTITY_SCALE) {
            throw new ValidationException("The movement is invalid.",
                    List.of(new Violation("quantity", "SCALE", "At most three decimals.")));
        }
        if (m.type().needsReason() && (m.reason() == null || m.reason().isBlank())) {
            throw new BusinessException(ErrorCode.ADJUSTMENT_REASON_REQUIRED, "A reason is required for an adjustment.");
        }
    }

    private static String reason(Movement m) {
        return m.reason() == null ? null : m.reason().trim();
    }
}
