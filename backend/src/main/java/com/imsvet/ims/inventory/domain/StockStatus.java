package com.imsvet.ims.inventory.domain;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * BR-STOCK-013, ADR-080: the stock status of a product from its sellable quantity (BR-STOCK-011): out when 0, low
 * when at most the minimum stock, otherwise in stock; below reorder when it has a reorder point and is at or under it.
 */
public enum StockStatus {
    IN_STOCK, LOW, OUT;

    public static StockStatus of(BigDecimal sellable, BigDecimal minStock) {
        if (sellable.signum() == 0) {
            return OUT;
        }
        return sellable.compareTo(minStock) <= 0 ? LOW : IN_STOCK;
    }

    public static boolean belowReorder(BigDecimal sellable, BigDecimal reorderPoint) {
        return reorderPoint.signum() > 0 && sellable.compareTo(reorderPoint) <= 0;
    }

    /** The API value. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
