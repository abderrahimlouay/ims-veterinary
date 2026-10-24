package com.imsvet.ims.purchasing.application;

import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.catalog.application.ProductLookup.UnitRef;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.purchasing.application.OrderViews.PricePrefill;
import com.imsvet.ims.purchasing.domain.SupplierPrice;
import com.imsvet.ims.purchasing.infrastructure.SupplierPriceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The price suggestion for an order line (ADR-099): the supplier's latest price-history entry in force on the
 * business date (entries of a cancelled receipt excluded from slice 6, ADR-106), else the product's reference
 * purchase price; converted from the base unit to the ordered unit, 4 decimals HALF_UP (ADR-094); VAT rate of the
 * product. A suggestion only: the order's amounts are always recomputed by the server.
 */
@Service
public class OrderPrefill {

    private static final int PRICE_SCALE = 4;

    private final SupplierPriceRepository prices;
    private final SupplierDirectory suppliers;
    private final ProductLookup products;
    private final BusinessCalendar calendar;

    public OrderPrefill(SupplierPriceRepository prices, SupplierDirectory suppliers, ProductLookup products,
            BusinessCalendar calendar) {
        this.prices = prices;
        this.suppliers = suppliers;
        this.products = products;
        this.calendar = calendar;
    }

    @PreAuthorize("hasAuthority('purchase.create')")
    @Transactional(readOnly = true)
    public PricePrefill prefill(long supplierId, long productId, Long unitId) {
        suppliers.find(supplierId);
        ProductRef product = products.find(productId)
                .orElseThrow(() -> OrderLineValidator.invalid("productId", "UNKNOWN", "Unknown product."));
        BigDecimal factor = BigDecimal.ONE;
        if (unitId != null) {
            factor = products.unit(productId, unitId).map(UnitRef::factorToBase)
                    .orElseThrow(() -> OrderLineValidator.invalid("unitId", "UNKNOWN", "Not a unit of this product."));
        }
        List<SupplierPrice> latest = prices.inForce(supplierId, productId, calendar.today(), PageRequest.of(0, 1));
        if (latest.isEmpty()) {
            return new PricePrefill(perUnit(product.purchasePrice(), factor), BigDecimal.ZERO.setScale(2),
                    product.vatRate(), "product");
        }
        SupplierPrice price = latest.getFirst();
        return new PricePrefill(perUnit(price.unitPrice(), factor), price.discountPct(), product.vatRate(), "history");
    }

    private static BigDecimal perUnit(BigDecimal basePrice, BigDecimal factor) {
        return basePrice.multiply(factor).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }
}
