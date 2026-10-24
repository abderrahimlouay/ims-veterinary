package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.inventory.domain.LotCost;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** ADR-098: weighted average on the current balance, 4 decimals HALF_UP; an empty lot takes the entry cost. */
class LotCostTest {

    @Test
    void anEmptyLotTakesTheEntryCost() {
        assertThat(cost("0", "0.5000", "12", "0.3500")).isEqualTo("0.3500");
        assertThat(cost("0.000", "9.9999", "1", "0")).isEqualTo("0.0000");
    }

    @Test
    void anExistingLotAveragesOnItsBalance() {
        assertThat(cost("10", "0.3000", "5", "0.3500")).isEqualTo("0.3167");
        assertThat(cost("10", "0.3000", "10", "0.3000")).isEqualTo("0.3000");
        assertThat(cost("2.5", "1.2000", "0.5", "0.6000")).isEqualTo("1.1000");
    }

    @Test
    void theAverageIsRoundedHalfUp() {
        // (1 × 0.0001 + 1 × 0.0000) / 2 = 0.00005 → 0.0001
        assertThat(cost("1", "0.0001", "1", "0")).isEqualTo("0.0001");
        // (3 × 0.0001 + 5 × 0.0000) / 8 = 0.0000375 → 0.0000
        assertThat(cost("3", "0.0001", "5", "0")).isEqualTo("0.0000");
    }

    private static String cost(String balance, String cost, String quantity, String entryCost) {
        return LotCost.weightedAverage(new BigDecimal(balance), new BigDecimal(cost), new BigDecimal(quantity),
                new BigDecimal(entryCost)).toPlainString();
    }
}
