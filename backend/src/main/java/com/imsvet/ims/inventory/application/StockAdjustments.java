package com.imsvet.ims.inventory.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.inventory.application.LotViews.MovementEntry;
import com.imsvet.ims.inventory.application.StockService.Movement;
import com.imsvet.ims.inventory.domain.Lot;
import com.imsvet.ims.inventory.domain.MovementType;
import com.imsvet.ims.inventory.domain.StockMovement;
import com.imsvet.ims.inventory.infrastructure.LotRepository;
import com.imsvet.ims.inventory.infrastructure.MovementReadRepository;
import com.imsvet.ims.users.application.UserNames;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual adjustments (W7, ADR-085, FR-STOCK-005): breakage, expiry, theft or internal use, a quantity > 0 in base
 * units and a mandatory reason (BR-STOCK-006/008). Posts one negative movement through {@link StockService} (never
 * below zero) and audits {@code STOCK_CORRECTION} with old and new quantity in the same transaction. Allowed on any
 * lot status: it is how unsellable stock leaves (ADR-060).
 */
@Service
public class StockAdjustments {

    public record AdjustmentInput(long lotId, String type, BigDecimal quantity, String reason) {
    }

    private static final Set<MovementType> MANUAL = Set.of(MovementType.ADJUSTMENT_BREAKAGE,
            MovementType.ADJUSTMENT_EXPIRY, MovementType.ADJUSTMENT_THEFT, MovementType.ADJUSTMENT_INTERNAL_USE);

    private static final int QUANTITY_SCALE = 3;

    private final LotRepository lots;
    private final StockService stock;
    private final MovementReadRepository movements;
    private final ProductLookup products;
    private final UserNames userNames;
    private final AuditRecorder audit;

    public StockAdjustments(LotRepository lots, StockService stock, MovementReadRepository movements,
            ProductLookup products, UserNames userNames, AuditRecorder audit) {
        this.lots = lots;
        this.stock = stock;
        this.movements = movements;
        this.products = products;
        this.userNames = userNames;
        this.audit = audit;
    }

    @PreAuthorize("hasAuthority('stock.adjust')")
    @Transactional
    public MovementEntry adjust(AdjustmentInput in) {
        MovementType type = manualType(in.type());
        Lot lot = lots.findByIdForUpdate(in.lotId()).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Lot not found.", Map.of("id", in.lotId())));
        ProductRef product = products.find(lot.productId()).orElseThrow();
        BigDecimal quantity = quantity(in.quantity(), product.divisible());
        if (in.reason() == null || in.reason().isBlank()) {
            throw new BusinessException(ErrorCode.ADJUSTMENT_REASON_REQUIRED, "A reason is required for an adjustment.");
        }
        BigDecimal before = lot.quantityOnHand();
        long movementId = stock.post(List.of(new Movement(lot.id(), lot.productId(), type, quantity.negate(),
                in.reason(), "adjustment", null))).getFirst();
        Map<String, Object> oldValues = Map.of("quantity", before.toPlainString());
        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("quantity", lot.quantityOnHand().toPlainString());
        newValues.put("type", type.code());
        newValues.put("reason", in.reason().trim());
        newValues.put("movementId", movementId);
        audit.record(AuditAction.STOCK_CORRECTION, "lots", lot.id(), oldValues, newValues);
        StockMovement movement = movements.findById(movementId).orElseThrow();
        return LotViews.movement(movement, lot, product,
                userNames.fullNames(List.of(movement.userId())).get(movement.userId()));
    }

    private static MovementType manualType(String code) {
        return MANUAL.stream().filter(t -> t.code().equals(code)).findFirst()
                .orElseThrow(() -> invalid("type", "UNKNOWN", "Breakage, expiry, theft or internal use only."));
    }

    /** > 0 in base units, at most 3 decimals, whole for a non-divisible product (ADR-050). */
    private static BigDecimal quantity(BigDecimal quantity, boolean divisible) {
        if (quantity == null || quantity.signum() <= 0) {
            throw invalid("quantity", "POSITIVE", "The quantity must be greater than zero.");
        }
        if (quantity.stripTrailingZeros().scale() > QUANTITY_SCALE) {
            throw invalid("quantity", "SCALE", "At most three decimals.");
        }
        if (!divisible && quantity.stripTrailingZeros().scale() > 0) {
            throw invalid("quantity", "WHOLE_NUMBER", "This product is counted in whole units.");
        }
        return quantity.setScale(QUANTITY_SCALE);
    }

    private static ValidationException invalid(String field, String code, String message) {
        return new ValidationException("The adjustment is invalid.", List.of(new Violation(field, code, message)));
    }
}
