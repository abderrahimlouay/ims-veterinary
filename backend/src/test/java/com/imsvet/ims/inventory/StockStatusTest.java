package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.inventory.domain.StockStatus;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** BR-STOCK-013, ADR-080: out when sellable = 0, low when 0 < sellable ≤ min, otherwise in stock; below reorder. */
class StockStatusTest {

    @ParameterizedTest
    @CsvSource({ "0, 10, OUT", "0.001, 10, LOW", "10, 10, LOW", "10.001, 10, IN_STOCK", "5, 0, IN_STOCK", "0, 0, OUT" })
    void statusFollowsSellableStockAndMinimum(String sellable, String min, StockStatus status) {
        assertThat(StockStatus.of(new BigDecimal(sellable), new BigDecimal(min))).isEqualTo(status);
    }

    @ParameterizedTest
    @CsvSource({ "20, 20, true", "20.001, 20, false", "0, 20, true", "0, 0, false", "5, 0, false" })
    void belowReorderNeedsAReorderPoint(String sellable, String reorderPoint, boolean below) {
        assertThat(StockStatus.belowReorder(new BigDecimal(sellable), new BigDecimal(reorderPoint))).isEqualTo(below);
    }
}
