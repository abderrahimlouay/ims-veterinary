package com.imsvet.ims.catalog.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.catalog.application.ProductViews.UnitView;
import com.imsvet.ims.catalog.domain.Product;
import com.imsvet.ims.catalog.domain.ProductUnit;
import com.imsvet.ims.catalog.infrastructure.ProductRepository;
import com.imsvet.ims.catalog.infrastructure.ProductUnitRepository;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.security.Permissions;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Packaging units of a product (FR-PROD-006/007). BR-UNIT-001: factor > 0 with at most 4 decimals, whole for a
 * non-divisible product (ADR-065), name unique within the product. ADR-063: the unit price is optional (2
 * decimals); setting, changing or clearing it needs {@code price.edit} and, after creation, is audited
 * {@code PRICE_CHANGE} before the save (BR-PROD-006). BR-UNIT-004: the factor of a unit in use is locked.
 */
@Service
public class ProductUnits {

    /** The request fields; {@code salePrice} {@code null} means derived (ADR-063), also on update. */
    public record UnitInput(String name, BigDecimal factorToBase, BigDecimal salePrice, boolean sellable) {
    }

    private static final String PRICE_EDIT = "price.edit";

    private static final int FACTOR_SCALE = 4;

    private static final int UNIT_PRICE_SCALE = 2;

    private final ProductRepository products;
    private final ProductUnitRepository units;
    private final List<UnitReferences> references;
    private final AuditRecorder audit;

    public ProductUnits(ProductRepository products, ProductUnitRepository units, List<UnitReferences> references,
            AuditRecorder audit) {
        this.products = products;
        this.units = units;
        this.references = List.copyOf(references);
        this.audit = audit;
    }

    @PreAuthorize("hasAuthority('product.read')")
    @Transactional(readOnly = true)
    public List<UnitView> list(long productId) {
        findProduct(productId);
        return units.findByProductId(productId).stream().map(ProductViews::unit).toList();
    }

    @PreAuthorize("hasAuthority('product.update')")
    @Transactional
    public UnitView add(long productId, UnitInput input) {
        Product product = findProduct(productId);
        Valid valid = validate(input, product.divisible());
        if (valid.salePrice() != null && !Permissions.granted(PRICE_EDIT)) {
            throw new AccessDeniedException("Setting a unit price needs price.edit");
        }
        rejectDuplicateName(productId, valid.name(), null);
        return ProductViews.unit(save(new ProductUnit(product, valid.name(), valid.factorToBase(), valid.salePrice(),
                input.sellable())));
    }

    @PreAuthorize("hasAuthority('product.update')")
    @Transactional
    public UnitView update(long productId, long unitId, UnitInput input, long version) {
        Product product = findProduct(productId);
        ProductUnit unit = units.findByIdAndProductId(unitId, productId).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Unit not found.", Map.of("id", unitId)));
        if (unit.version() != version) {
            throw new ObjectOptimisticLockingFailureException(ProductUnit.class, unitId);
        }
        Valid valid = validate(input, product.divisible());
        boolean priceChanged = !samePrice(unit.salePrice(), valid.salePrice());
        if (priceChanged && !Permissions.granted(PRICE_EDIT)) {
            throw new AccessDeniedException("Changing a unit price needs price.edit");
        }
        if (unit.factorToBase().compareTo(valid.factorToBase()) != 0
                && references.stream().anyMatch(r -> r.referencesUnit(unitId))) {
            throw new BusinessException(ErrorCode.UNIT_FACTOR_LOCKED,
                    "This unit is used by documents; define a new unit instead.", Map.of("field", "factorToBase"));
        }
        rejectDuplicateName(productId, valid.name(), unitId);
        if (priceChanged) {
            audit.record(AuditAction.PRICE_CHANGE, "product_units", unit.id(), priceValue(unit.salePrice()),
                    priceValue(valid.salePrice()));
            unit.setSalePrice(valid.salePrice());
        }
        unit.define(valid.name(), valid.factorToBase(), input.sellable());
        return ProductViews.unit(save(unit));
    }

    private record Valid(String name, BigDecimal factorToBase, BigDecimal salePrice) {
    }

    private static Valid validate(UnitInput in, boolean divisible) {
        List<Violation> violations = new ArrayList<>();
        BigDecimal factor = in.factorToBase();
        if (factor.signum() <= 0) {
            violations.add(new Violation("factorToBase", "POSITIVE", "The factor must be greater than zero."));
        } else if (factor.stripTrailingZeros().scale() > FACTOR_SCALE) {
            violations.add(new Violation("factorToBase", "SCALE", "At most four decimals."));
        } else if (!divisible && factor.stripTrailingZeros().scale() > 0) {
            violations.add(new Violation("factorToBase", "WHOLE_NUMBER", "This product is counted in whole units."));
        }
        BigDecimal price = in.salePrice();
        if (price != null && price.signum() < 0) {
            violations.add(new Violation("salePrice", "NOT_NEGATIVE", "A price cannot be negative."));
        } else if (price != null && price.stripTrailingZeros().scale() > UNIT_PRICE_SCALE) {
            violations.add(new Violation("salePrice", "SCALE", "At most two decimals."));
        }
        if (!violations.isEmpty()) {
            throw new ValidationException("The unit contains invalid fields.", violations);
        }
        return new Valid(in.name().trim(), factor.setScale(FACTOR_SCALE),
                price == null ? null : price.setScale(UNIT_PRICE_SCALE));
    }

    private ProductUnit save(ProductUnit unit) {
        try {
            return units.saveAndFlush(unit);
        } catch (DataIntegrityViolationException e) {
            throw duplicateName();
        }
    }

    private Product findProduct(long id) {
        return products.findById(id).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Product not found.", Map.of("id", id)));
    }

    private void rejectDuplicateName(long productId, String name, Long self) {
        if (units.findByProductIdAndName(productId, name).filter(u -> !u.id().equals(self)).isPresent()) {
            throw duplicateName();
        }
    }

    private static BusinessException duplicateName() {
        return new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "This product already has a unit with this name.",
                Map.of("field", "name"));
    }

    private static boolean samePrice(BigDecimal before, BigDecimal after) {
        return before == null ? after == null : after != null && before.compareTo(after) == 0;
    }

    /** {@code null} (derived price) is kept as a JSON null in the audit values. */
    private static Map<String, Object> priceValue(BigDecimal price) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("salePrice", price == null ? null : price.toPlainString());
        return values;
    }
}
