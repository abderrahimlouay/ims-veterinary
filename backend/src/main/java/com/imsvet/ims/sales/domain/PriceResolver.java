package com.imsvet.ims.sales.domain;

import java.math.BigDecimal;

/**
 * The unit price and discount of a sales line (BR-SALE-006/007, ADR-107, ADR-113). Pure: the caller gathers the
 * facts and the user's rights. The most specific price wins and prices never stack: a manual line price (needs
 * {@code price.edit}), else the customer's own price × the unit factor, else the unit's sale price, else the product
 * price × the unit factor. The entitlement is the category discount, only when the customer has no own price for the
 * product; the line discount is the manual one when given (it replaces, never adds to, the entitlement), else the
 * entitlement; above the entitlement needs {@code sale.discount}. An archived product gets no price. Nothing is
 * rounded here: line totals round (ADR-093).
 */
public final class PriceResolver {

    /**
     * Prices per base unit except {@code unitSalePrice} (per unit, {@code null} when derived); {@code unitFactor} 1
     * for the base unit; {@code customerPricePerBase} and {@code categoryDiscountPct} {@code null} when absent.
     */
    public record Facts(boolean productArchived, BigDecimal productPricePerBase, BigDecimal unitFactor,
            BigDecimal unitSalePrice, BigDecimal customerPricePerBase, BigDecimal categoryDiscountPct) {
    }

    /** {@code manualUnitPrice} per unit and {@code manualDiscountPct}, {@code null} when not given. */
    public record Request(BigDecimal manualUnitPrice, BigDecimal manualDiscountPct, boolean mayEditPrice,
            boolean mayDiscount) {
    }

    public enum Source {
        MANUAL, CUSTOMER, UNIT, PRODUCT
    }

    public record Resolution(BigDecimal unitPrice, Source source, BigDecimal discountPct, BigDecimal entitlementPct) {
    }

    /** A line the user may not price this way. */
    public static final class PriceRefusal extends RuntimeException {

        public enum Reason {
            PRODUCT_ARCHIVED, MANUAL_PRICE_NOT_ALLOWED, DISCOUNT_ABOVE_ENTITLEMENT, DISCOUNT_OUT_OF_RANGE
        }

        private final Reason reason;

        PriceRefusal(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PriceResolver() {
    }

    public static Resolution resolve(Facts facts, Request request) {
        if (facts.productArchived()) {
            throw new PriceRefusal(PriceRefusal.Reason.PRODUCT_ARCHIVED);
        }
        BigDecimal price;
        Source source;
        if (request.manualUnitPrice() != null) {
            if (!request.mayEditPrice()) {
                throw new PriceRefusal(PriceRefusal.Reason.MANUAL_PRICE_NOT_ALLOWED);
            }
            price = request.manualUnitPrice();
            source = Source.MANUAL;
        } else if (facts.customerPricePerBase() != null) {
            price = facts.customerPricePerBase().multiply(facts.unitFactor());
            source = Source.CUSTOMER;
        } else if (facts.unitSalePrice() != null) {
            price = facts.unitSalePrice();
            source = Source.UNIT;
        } else {
            price = facts.productPricePerBase().multiply(facts.unitFactor());
            source = Source.PRODUCT;
        }
        BigDecimal entitlement = facts.customerPricePerBase() != null || facts.categoryDiscountPct() == null
                ? BigDecimal.ZERO : facts.categoryDiscountPct();
        BigDecimal discount = request.manualDiscountPct() == null ? entitlement : request.manualDiscountPct();
        if (discount.signum() < 0 || discount.compareTo(HUNDRED) > 0) {
            throw new PriceRefusal(PriceRefusal.Reason.DISCOUNT_OUT_OF_RANGE);
        }
        if (discount.compareTo(entitlement) > 0 && !request.mayDiscount()) {
            throw new PriceRefusal(PriceRefusal.Reason.DISCOUNT_ABOVE_ENTITLEMENT);
        }
        return new Resolution(price, source, discount, entitlement);
    }
}
