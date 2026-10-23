package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.inventory.domain.ReorderQuantity;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** BR-STOCK-010, ADR-077: max − sellable in base units, rounded up to whole for a non-divisible product. */
class ReorderQuantityTest {

    @Test
    void maxMinusSellable() {
        assertThat(ReorderQuantity.suggested(new BigDecimal("50"), new BigDecimal("4.5"), true))
                .contains(new BigDecimal("45.500"));
    }

    @Test
    void roundedUpToWholeUnitsWhenNotDivisible() {
        assertThat(ReorderQuantity.suggested(new BigDecimal("50"), new BigDecimal("4.5"), false))
                .contains(new BigDecimal("46.000"));
        assertThat(ReorderQuantity.suggested(new BigDecimal("50"), new BigDecimal("4.001"), false))
                .contains(new BigDecimal("46.000"));
    }

    @Test
    void nothingWithoutMaxOrWhenTheMaxIsAlreadyReached() {
        assertThat(ReorderQuantity.suggested(null, new BigDecimal("4"), true)).isEqualTo(Optional.empty());
        assertThat(ReorderQuantity.suggested(new BigDecimal("5"), new BigDecimal("5"), true)).isEmpty();
        assertThat(ReorderQuantity.suggested(new BigDecimal("5"), new BigDecimal("6"), true)).isEmpty();
    }
}
