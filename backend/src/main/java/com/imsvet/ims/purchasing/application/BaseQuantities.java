package com.imsvet.ims.purchasing.application;

import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.catalog.application.ProductLookup.UnitRef;
import com.imsvet.ims.catalog.domain.UnitConversion;
import com.imsvet.ims.catalog.domain.UnitConversion.InexactConversionException;
import java.math.BigDecimal;

/**
 * A line's quantity in a unit of its product (base unit when none), converted exactly to base units (ADR-065):
 * {@code unitId} {@code UNKNOWN}; {@code quantity} {@code REQUIRED}, {@code POSITIVE}, {@code SCALE},
 * {@code WHOLE_NUMBER}; {@code prefix} names the line in the field errors.
 */
final class BaseQuantities {

    private BaseQuantities() {
    }

    static BigDecimal toBase(ProductLookup products, ProductRef product, Long unitId, BigDecimal quantity,
            String prefix) {
        BigDecimal factor = BigDecimal.ONE;
        if (unitId != null) {
            UnitRef unit = products.unit(product.id(), unitId).orElseThrow(
                    () -> OrderLineValidator.invalid(prefix + "unitId", "UNKNOWN", "Not a unit of this product."));
            factor = unit.factorToBase();
        }
        if (quantity == null) {
            throw OrderLineValidator.invalid(prefix + "quantity", "REQUIRED", "The quantity is required.");
        }
        try {
            return UnitConversion.toBase(quantity, factor, product.divisible());
        } catch (InexactConversionException e) {
            throw switch (e.refusal()) {
                case NOT_POSITIVE -> OrderLineValidator.invalid(prefix + "quantity", "POSITIVE",
                        "The quantity must be greater than zero.");
                case SCALE -> OrderLineValidator.invalid(prefix + "quantity", "SCALE",
                        "This quantity cannot be converted exactly.");
                case WHOLE_NUMBER -> OrderLineValidator.invalid(prefix + "quantity", "WHOLE_NUMBER",
                        "This product is counted in whole units.");
            };
        }
    }
}
