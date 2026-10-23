package com.imsvet.ims.inventory.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.catalog.application.ProductLookup.UnitRef;
import com.imsvet.ims.catalog.domain.UnitConversion;
import com.imsvet.ims.catalog.domain.UnitConversion.InexactConversionException;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.inventory.application.LotIdentities.Found;
import com.imsvet.ims.inventory.application.LotViews.OpeningResult;
import com.imsvet.ims.inventory.application.StockService.Movement;
import com.imsvet.ims.inventory.domain.Lot;
import com.imsvet.ims.inventory.domain.LotStatus;
import com.imsvet.ims.inventory.domain.MovementType;
import com.imsvet.ims.inventory.domain.StockMovement;
import com.imsvet.ims.inventory.infrastructure.MovementReadRepository;
import com.imsvet.ims.settings.application.SettingsProvider;
import com.imsvet.ims.settings.domain.SettingDefinition;
import com.imsvet.ims.users.application.UserNames;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opening stock (W10a, FR-STOCK-007, BR-STOCK-014): allowed only while {@code opening_stock_open} is true (ADR-082);
 * product, lot number, expiry and unit cost required (BR-LOT-001, ADR-083); quantity in a unit of the product,
 * converted exactly (ADR-065); lot found or created by (product, lot number) under lock: another expiry, another
 * unit cost (until OD-029) or a recalled lot refused, a blocked lot accepted (ADR-084). One {@code opening_stock}
 * movement through {@link StockService}, audited {@code OPENING_STOCK}, all in one transaction.
 */
@Service
public class OpeningStock {

    public record OpeningInput(long productId, String lotNumber, LocalDate expiryDate, BigDecimal quantity, Long unitId,
            BigDecimal unitCost) {
    }

    private static final int LOT_NUMBER_MAX = 60;

    private static final int COST_SCALE = 4;

    private final SettingsProvider settings;
    private final ProductLookup products;
    private final LotIdentities identities;
    private final StockService stock;
    private final MovementReadRepository movements;
    private final ExpiryPolicies policies;
    private final UserNames userNames;
    private final AuditRecorder audit;

    public OpeningStock(SettingsProvider settings, ProductLookup products, LotIdentities identities, StockService stock,
            MovementReadRepository movements, ExpiryPolicies policies, UserNames userNames, AuditRecorder audit) {
        this.settings = settings;
        this.products = products;
        this.identities = identities;
        this.stock = stock;
        this.movements = movements;
        this.policies = policies;
        this.userNames = userNames;
        this.audit = audit;
    }

    @PreAuthorize("hasAuthority('stock.adjust')")
    @Transactional
    public OpeningResult enter(OpeningInput in) {
        if (!"true".equals(settings.value(SettingDefinition.OPENING_STOCK_OPEN))) {
            throw new BusinessException(ErrorCode.OPENING_STOCK_CLOSED,
                    "The opening-stock period is closed; stock found now goes through an inventory count.");
        }
        ProductRef product = products.find(in.productId()).orElseThrow(() -> new BusinessException(
                ErrorCode.NOT_FOUND, "Product not found.", Map.of("id", in.productId())));
        if (product.archived()) {
            throw new BusinessException(ErrorCode.PRODUCT_ARCHIVED, "This product is archived.",
                    Map.of("productId", in.productId()));
        }
        String lotNumber = validate(in);
        BigDecimal quantity = toBase(in, product);
        BigDecimal unitCost = in.unitCost().setScale(COST_SCALE);
        Found found = identities.findOrCreate(product.id(), lotNumber, in.expiryDate(), unitCost);
        Lot lot = found.lot();
        if (!found.created()) {
            if (lot.status() == LotStatus.RECALLED) {
                throw new BusinessException(ErrorCode.LOT_RECALLED, "This lot is recalled.", Map.of("lotId", lot.id()));
            }
            if (lot.unitCost().compareTo(unitCost) != 0) {
                throw invalid(List.of(new Violation("unitCost", "COST_MISMATCH",
                        "This lot already exists with another unit cost.")));
            }
        }
        long movementId = stock.post(List.of(new Movement(lot.id(), product.id(), MovementType.OPENING_STOCK, quantity,
                null, "opening_stock", null))).getFirst();
        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("lotNumber", lot.lotNumber());
        newValues.put("expiryDate", lot.expiryDate().toString());
        newValues.put("quantity", quantity.toPlainString());
        newValues.put("unitCost", unitCost.toPlainString());
        newValues.put("created", found.created());
        newValues.put("movementId", movementId);
        audit.record(AuditAction.OPENING_STOCK, "lots", lot.id(), null, newValues);
        StockMovement movement = movements.findById(movementId).orElseThrow();
        String operator = userNames.fullNames(List.of(movement.userId())).get(movement.userId());
        return new OpeningResult(LotViews.detail(lot, product, policies.current(), LotQueries.blockedByName(lot, userNames)),
                LotViews.movement(movement, lot, product, operator));
    }

    /** Returns the trimmed lot number. */
    private static String validate(OpeningInput in) {
        List<Violation> violations = new ArrayList<>();
        String lotNumber = in.lotNumber() == null ? "" : in.lotNumber().trim();
        if (lotNumber.isEmpty()) {
            violations.add(new Violation("lotNumber", "REQUIRED", "The lot number is required."));
        } else if (lotNumber.length() > LOT_NUMBER_MAX) {
            violations.add(new Violation("lotNumber", "SIZE", "At most 60 characters."));
        }
        if (in.expiryDate() == null) {
            violations.add(new Violation("expiryDate", "REQUIRED", "The expiry date is required."));
        }
        if (in.unitCost() == null) {
            violations.add(new Violation("unitCost", "REQUIRED", "The unit cost is required."));
        } else if (in.unitCost().signum() < 0) {
            violations.add(new Violation("unitCost", "NOT_NEGATIVE", "A cost cannot be negative."));
        } else if (in.unitCost().stripTrailingZeros().scale() > COST_SCALE) {
            violations.add(new Violation("unitCost", "SCALE", "At most four decimals."));
        }
        if (!violations.isEmpty()) {
            throw invalid(violations);
        }
        return lotNumber;
    }

    private BigDecimal toBase(OpeningInput in, ProductRef product) {
        BigDecimal factor = BigDecimal.ONE;
        if (in.unitId() != null) {
            UnitRef unit = products.unit(product.id(), in.unitId())
                    .orElseThrow(() -> invalid(List.of(new Violation("unitId", "UNKNOWN", "Not a unit of this product."))));
            factor = unit.factorToBase();
        }
        if (in.quantity() == null) {
            throw invalid(List.of(new Violation("quantity", "REQUIRED", "The quantity is required.")));
        }
        try {
            return UnitConversion.toBase(in.quantity(), factor, product.divisible());
        } catch (InexactConversionException e) {
            throw switch (e.refusal()) {
                case NOT_POSITIVE -> invalid(List.of(new Violation("quantity", "POSITIVE", "The quantity must be greater than zero.")));
                case SCALE -> invalid(List.of(new Violation("quantity", "SCALE", "This quantity cannot be converted exactly.")));
                case WHOLE_NUMBER -> invalid(List.of(new Violation("quantity", "WHOLE_NUMBER", "This product is counted in whole units.")));
            };
        }
    }

    private static ValidationException invalid(List<Violation> violations) {
        return new ValidationException("The opening-stock entry is invalid.", violations);
    }
}
