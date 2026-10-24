package com.imsvet.ims.purchasing.application;

import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.catalog.application.ProductLookup.UnitRef;
import com.imsvet.ims.catalog.domain.UnitConversion;
import com.imsvet.ims.catalog.domain.UnitConversion.InexactConversionException;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.purchasing.application.PurchaseOrders.RequestedLine;
import com.imsvet.ims.purchasing.domain.PurchaseOrder.LineInput;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Checks the lines of a purchase order against the catalogue before anything is locked or numbered: product known,
 * not archived, once per order (ADR-105); unit of that product; quantity > 0 converted exactly to base units
 * (ADR-065); price ≥ 0 with 4 decimals per ordered unit (ADR-094); discount and VAT 0–100 with 2 decimals, VAT
 * defaulting to the product's (ADR-105). Client amounts are never read.
 */
@Component
class OrderLineValidator {

    private static final int PRICE_SCALE = 4;
    private static final int PERCENT_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ProductLookup products;

    OrderLineValidator(ProductLookup products) {
        this.products = products;
    }

    List<LineInput> validate(List<RequestedLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw invalid("lines", "REQUIRED", "An order has at least one line.");
        }
        List<LineInput> valid = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            valid.add(line(lines.get(i), "lines[" + i + "].", seen));
        }
        return valid;
    }

    private LineInput line(RequestedLine in, String prefix, Set<Long> seen) {
        if (in.productId() == null) {
            throw invalid(prefix + "productId", "REQUIRED", "The product is required.");
        }
        ProductRef product = products.find(in.productId())
                .orElseThrow(() -> invalid(prefix + "productId", "UNKNOWN", "Unknown product."));
        if (product.archived()) {
            throw new BusinessException(ErrorCode.PRODUCT_ARCHIVED, "This product is archived.",
                    Map.of("productId", product.id()));
        }
        if (!seen.add(product.id())) {
            throw invalid(prefix + "productId", "DUPLICATE", "A product appears once per order.");
        }
        BigDecimal factor = BigDecimal.ONE;
        if (in.unitId() != null) {
            UnitRef unit = products.unit(product.id(), in.unitId())
                    .orElseThrow(() -> invalid(prefix + "unitId", "UNKNOWN", "Not a unit of this product."));
            factor = unit.factorToBase();
        }
        if (in.quantity() == null) {
            throw invalid(prefix + "quantity", "REQUIRED", "The quantity is required.");
        }
        BigDecimal base;
        try {
            base = UnitConversion.toBase(in.quantity(), factor, product.divisible());
        } catch (InexactConversionException e) {
            throw switch (e.refusal()) {
                case NOT_POSITIVE -> invalid(prefix + "quantity", "POSITIVE", "The quantity must be greater than zero.");
                case SCALE -> invalid(prefix + "quantity", "SCALE", "This quantity cannot be converted exactly.");
                case WHOLE_NUMBER -> invalid(prefix + "quantity", "WHOLE_NUMBER", "This product is counted in whole units.");
            };
        }
        BigDecimal price = scaled(in.unitPrice(), prefix + "unitPrice", PRICE_SCALE, null);
        BigDecimal discount = percent(in.discountPct(), prefix + "discountPct", BigDecimal.ZERO);
        BigDecimal vat = percent(in.vatRate(), prefix + "vatRate", product.vatRate());
        return new LineInput(product.id(), in.unitId(), in.quantity().setScale(UnitConversion.BASE_SCALE), base,
                price, discount, vat);
    }

    private static BigDecimal percent(BigDecimal value, String field, BigDecimal fallback) {
        BigDecimal v = value == null ? fallback : value;
        if (v.signum() < 0 || v.compareTo(HUNDRED) > 0) {
            throw invalid(field, "RANGE", "Between 0 and 100.");
        }
        return scaled(v, field, PERCENT_SCALE, null);
    }

    private static BigDecimal scaled(BigDecimal value, String field, int scale, BigDecimal fallback) {
        BigDecimal v = value == null ? fallback : value;
        if (v == null) {
            throw invalid(field, "REQUIRED", "This value is required.");
        }
        if (v.signum() < 0) {
            throw invalid(field, "NOT_NEGATIVE", "Cannot be negative.");
        }
        if (v.stripTrailingZeros().scale() > scale) {
            throw invalid(field, "SCALE", "At most " + scale + " decimals.");
        }
        return v.setScale(scale);
    }

    static ValidationException invalid(String field, String code, String message) {
        return new ValidationException("The purchase order is invalid.", List.of(new Violation(field, code, message)));
    }
}
