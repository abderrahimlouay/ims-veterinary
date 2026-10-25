package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.sales.domain.PriceResolver;
import com.imsvet.ims.sales.domain.PriceResolver.Facts;
import com.imsvet.ims.sales.domain.PriceResolver.PriceRefusal;
import com.imsvet.ims.sales.domain.PriceResolver.Request;
import com.imsvet.ims.sales.domain.PriceResolver.Resolution;
import com.imsvet.ims.sales.domain.PriceResolver.Source;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * ADR-107 and ADR-113, the precedence table: manual line price (price.edit) > the customer's own price × factor >
 * the unit's sale price > product price × factor; the category discount is the entitlement only without an own
 * price; a discount above it needs sale.discount; discounts never add up; an archived product gets no price; no
 * rounding (ADR-093: lines round, the resolver does not).
 */
class PriceResolverTest {

    /**
     * Columns: product price/base, unit factor, unit price, own price/base, category %, manual price, manual %,
     * price.edit, sale.discount → price, source, discount, entitlement.
     */
    @ParameterizedTest(name = "{index}: {13}")
    @CsvSource(delimiter = '|', nullValues = "-", value = {
            "0.5000|1|-|-|-|-|-|false|false|0.5000|PRODUCT|0|0|product price in the base unit",
            "0.5000|10|-|-|-|-|-|false|false|5.0000|PRODUCT|0|0|product price × factor",
            "0.5000|10|4.50|-|-|-|-|false|false|4.50|UNIT|0|0|the unit's own price beats the derived one",
            "0.5000|10|4.50|0.4000|-|-|-|false|false|4.0000|CUSTOMER|0|0|the customer's own price beats the unit price",
            "0.5000|10|4.50|-|5|-|-|false|false|4.50|UNIT|5|5|category discount on a price without own price",
            "0.5000|10|4.50|0.4000|5|-|-|false|false|4.0000|CUSTOMER|0|0|category discount ignored with an own price",
            "0.5000|1|-|-|-|-|-|false|false|0.5000|PRODUCT|0|0|a customer without category has no entitlement",
            "0.5000|1|-|-|5|-|3|false|false|0.5000|PRODUCT|3|5|a manual discount within the entitlement is free",
            "0.5000|1|-|-|5|-|8|false|true|0.5000|PRODUCT|8|5|above the entitlement with sale.discount",
            "0.5000|1|-|-|5|-|8|false|true|0.5000|PRODUCT|8|5|no stacking: 8 replaces 5, it is not 13",
            "0.5000|1|-|0.4000|5|-|2|false|true|0.4000|CUSTOMER|2|0|own price: any manual discount is above 0",
            "0.5000|1|-|0.4000|-|0.3800|-|true|false|0.3800|MANUAL|0|0|manual price with price.edit wins",
            "0.5000|3|-|0.3333|-|-|-|false|false|0.9999|CUSTOMER|0|0|no rounding: 0.3333 × 3",
            "0.4444|2.5|-|-|-|-|-|false|false|1.11100|PRODUCT|0|0|no rounding: 0.4444 × 2.5 keeps its scale" })
    void thePrecedenceTable(String product, String factor, String unit, String own, String category, String manual,
            String manualPct, boolean mayEditPrice, boolean mayDiscount, String price, Source source, String discount,
            String entitlement, String label) {
        Resolution r = PriceResolver.resolve(
                new Facts(false, dec(product), dec(factor), dec(unit), dec(own), dec(category)),
                new Request(dec(manual), dec(manualPct), mayEditPrice, mayDiscount));

        assertThat(r.unitPrice()).as(label).isEqualTo(new BigDecimal(price));
        assertThat(r.source()).as(label).isEqualTo(source);
        assertThat(r.discountPct()).as(label).isEqualByComparingTo(discount);
        assertThat(r.entitlementPct()).as(label).isEqualByComparingTo(entitlement);
    }

    @Test
    void aManualPriceNeedsPriceEdit() {
        assertRefused(new Facts(false, dec("0.5"), BigDecimal.ONE, null, null, null),
                new Request(dec("0.1"), null, false, true), PriceRefusal.Reason.MANUAL_PRICE_NOT_ALLOWED);
    }

    @Test
    void aDiscountAboveTheEntitlementNeedsSaleDiscount() {
        assertRefused(new Facts(false, dec("0.5"), BigDecimal.ONE, null, null, dec("5")),
                new Request(null, dec("5.01"), true, false), PriceRefusal.Reason.DISCOUNT_ABOVE_ENTITLEMENT);
        assertRefused(new Facts(false, dec("0.5"), BigDecimal.ONE, null, dec("0.4"), dec("5")),
                new Request(null, dec("1"), true, false), PriceRefusal.Reason.DISCOUNT_ABOVE_ENTITLEMENT);
    }

    @Test
    void aDiscountStaysBetween0And100() {
        assertRefused(new Facts(false, dec("0.5"), BigDecimal.ONE, null, null, null),
                new Request(null, dec("100.01"), true, true), PriceRefusal.Reason.DISCOUNT_OUT_OF_RANGE);
        assertRefused(new Facts(false, dec("0.5"), BigDecimal.ONE, null, null, null),
                new Request(null, dec("-1"), true, true), PriceRefusal.Reason.DISCOUNT_OUT_OF_RANGE);
    }

    @Test
    void anArchivedProductGetsNoPrice() {
        assertRefused(new Facts(true, dec("0.5"), BigDecimal.ONE, null, dec("0.4"), null),
                new Request(null, null, true, true), PriceRefusal.Reason.PRODUCT_ARCHIVED);
    }

    private static void assertRefused(Facts facts, Request request, PriceRefusal.Reason reason) {
        assertThatThrownBy(() -> PriceResolver.resolve(facts, request)).isInstanceOfSatisfying(PriceRefusal.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }

    private static BigDecimal dec(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
