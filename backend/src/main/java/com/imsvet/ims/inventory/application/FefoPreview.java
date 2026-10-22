package com.imsvet.ims.inventory.application;

import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.catalog.application.ProductLookup.UnitRef;
import com.imsvet.ims.catalog.domain.UnitConversion;
import com.imsvet.ims.catalog.domain.UnitConversion.InexactConversionException;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.inventory.application.LotViews.FefoLine;
import com.imsvet.ims.inventory.application.LotViews.FefoPreviewView;
import com.imsvet.ims.inventory.domain.AlertLevel;
import com.imsvet.ims.inventory.domain.ExpiryPolicy;
import com.imsvet.ims.inventory.domain.FefoAllocator;
import com.imsvet.ims.inventory.domain.FefoAllocator.Allocation;
import com.imsvet.ims.inventory.domain.FefoAllocator.Candidate;
import com.imsvet.ims.inventory.domain.Lot;
import com.imsvet.ims.inventory.infrastructure.LotRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * api.md §5.3: which lots FEFO would take for a quantity (BR-LOT-005). The quantity is converted exactly to base
 * units (BR-UNIT-002, ADR-065) with a sellable unit of the product (BR-UNIT-003); archived products are refused
 * (BR-PROD-004). Informational: nothing is reserved or locked, stock is re-validated at confirmation.
 */
@Service
public class FefoPreview {

    private final LotRepository lots;
    private final ProductLookup products;
    private final ExpiryPolicies policies;

    public FefoPreview(LotRepository lots, ProductLookup products, ExpiryPolicies policies) {
        this.lots = lots;
        this.products = products;
        this.policies = policies;
    }

    @PreAuthorize("hasAuthority('lot.read')")
    @Transactional(readOnly = true)
    public FefoPreviewView preview(long productId, BigDecimal quantity, Long unitId) {
        ProductRef product = products.find(productId).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Product not found.", Map.of("id", productId)));
        if (product.archived()) {
            throw new BusinessException(ErrorCode.PRODUCT_ARCHIVED, "This product is archived.",
                    Map.of("productId", productId));
        }
        BigDecimal factor = BigDecimal.ONE;
        if (unitId != null) {
            UnitRef unit = products.unit(productId, unitId)
                    .orElseThrow(() -> invalid("unitId", "UNKNOWN", "Not a unit of this product."));
            if (!unit.sellable()) {
                throw new BusinessException(ErrorCode.UNIT_NOT_SELLABLE, "This unit is not sold.",
                        Map.of("unitId", unitId));
            }
            factor = unit.factorToBase();
        }
        BigDecimal requested = toBase(quantity, factor, product.divisible());
        ExpiryPolicy policy = policies.current();
        List<Lot> productLots = lots.findByProductIdInFefoOrder(productId);
        Map<Long, Lot> byId = productLots.stream().collect(Collectors.toMap(Lot::id, Function.identity()));
        Allocation allocation = FefoAllocator.allocate(productLots.stream()
                .map(l -> new Candidate(l.id(), l.expiryDate(), l.quantityOnHand(), l.status())).toList(),
                requested, policy);
        return new FefoPreviewView(requested, allocation.lines().stream().map(line -> {
            Lot lot = byId.get(line.candidate().id());
            AlertLevel level = policy.level(lot.expiryDate());
            return new FefoLine(lot.id(), lot.lotNumber(), lot.expiryDate(), line.quantityBase(), lot.quantityOnHand(),
                    level == null ? null : level.code());
        }).toList(), allocation.shortfallBase());
    }

    private static BigDecimal toBase(BigDecimal quantity, BigDecimal factor, boolean divisible) {
        if (quantity.stripTrailingZeros().scale() > UnitConversion.BASE_SCALE) {
            throw invalid("quantity", "SCALE", "At most three decimals.");
        }
        try {
            return UnitConversion.toBase(quantity, factor, divisible);
        } catch (InexactConversionException e) {
            throw switch (e.refusal()) {
                case NOT_POSITIVE -> invalid("quantity", "POSITIVE", "The quantity must be greater than zero.");
                case SCALE -> invalid("quantity", "SCALE", "This quantity cannot be converted exactly.");
                case WHOLE_NUMBER -> invalid("quantity", "WHOLE_NUMBER", "This product is counted in whole units.");
            };
        }
    }

    private static ValidationException invalid(String field, String code, String message) {
        return new ValidationException("The FEFO request is invalid.", List.of(new Violation(field, code, message)));
    }
}
