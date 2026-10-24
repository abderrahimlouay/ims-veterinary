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
        assertThat(ReorderQuantity.suggested(new BigDecimal("50"), new BigDecimal("4.5"), BigDecimal.ZERO, true))
                .contains(new BigDecimal("45.500"));
    }

    @Test
    void roundedUpToWholeUnitsWhenNotDivisible() {
        assertThat(ReorderQuantity.suggested(new BigDecimal("50"), new BigDecimal("4.5"), BigDecimal.ZERO, false))
                .contains(new BigDecimal("46.000"));
        assertThat(ReorderQuantity.suggested(new BigDecimal("50"), new BigDecimal("4.001"), BigDecimal.ZERO, false))
                .contains(new BigDecimal("46.000"));
    }

    @Test
    void nothingWithoutMaxOrWhenTheMaxIsAlreadyReached() {
        assertThat(ReorderQuantity.suggested(null, new BigDecimal("4"), BigDecimal.ZERO, true)).isEqualTo(Optional.empty());
        assertThat(ReorderQuantity.suggested(new BigDecimal("5"), new BigDecimal("5"), BigDecimal.ZERO, true)).isEmpty();
        assertThat(ReorderQuantity.suggested(new BigDecimal("5"), new BigDecimal("6"), BigDecimal.ZERO, true)).isEmpty();
    }

    /** ADR-102: what is on open orders is subtracted; nothing when it already covers the need. */
    @Test
    void whatIsOnOrderIsSubtracted() {
        assertThat(ReorderQuantity.suggested(new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("50"), false))
                .contains(new BigDecimal("40.000"));
        assertThat(ReorderQuantity.suggested(new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("90"), false))
                .isEmpty();
        assertThat(ReorderQuantity.suggested(new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("89.5"), false))
                .contains(new BigDecimal("1.000"));
    }
}
