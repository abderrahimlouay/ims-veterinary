package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.inventory.domain.MovementType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** BR-STOCK-005, ADR-081: the ten types, their codes and their sign; adjustments carry a reason (BR-STOCK-006). */
class MovementTypeTest {

    @Test
    void entriesArePositiveExitsNegativeAndInventoryEither() {
        for (MovementType type : new MovementType[] { MovementType.PURCHASE_RECEIPT, MovementType.CUSTOMER_RETURN,
                MovementType.OPENING_STOCK, MovementType.SALE_CANCELLATION }) {
            assertThat(type.allows(BigDecimal.ONE)).as(type.code()).isTrue();
            assertThat(type.allows(BigDecimal.ONE.negate())).as(type.code()).isFalse();
        }
        for (MovementType type : new MovementType[] { MovementType.SALE, MovementType.SUPPLIER_RETURN,
                MovementType.RECEIPT_CANCELLATION, MovementType.ADJUSTMENT_BREAKAGE, MovementType.ADJUSTMENT_EXPIRY, MovementType.ADJUSTMENT_THEFT,
                MovementType.ADJUSTMENT_INTERNAL_USE }) {
            assertThat(type.allows(BigDecimal.ONE.negate())).as(type.code()).isTrue();
            assertThat(type.allows(BigDecimal.ONE)).as(type.code()).isFalse();
        }
        assertThat(MovementType.ADJUSTMENT_INVENTORY.allows(BigDecimal.ONE)).isTrue();
        assertThat(MovementType.ADJUSTMENT_INVENTORY.allows(BigDecimal.ONE.negate())).isTrue();
        for (MovementType type : MovementType.values()) {
            assertThat(type.allows(BigDecimal.ZERO)).as(type.code()).isFalse();
        }
    }

    @Test
    void codesAreTheDatabaseValuesAndAdjustmentsNeedAReason() {
        assertThat(MovementType.values()).hasSize(12);
        assertThat(MovementType.RECEIPT_CANCELLATION.code()).isEqualTo("receipt_cancellation");
        assertThat(MovementType.RECEIPT_CANCELLATION.needsReason()).isFalse();
        assertThat(MovementType.fromCode("adjustment_internal_use")).isEqualTo(MovementType.ADJUSTMENT_INTERNAL_USE);
        assertThat(MovementType.OPENING_STOCK.code()).isEqualTo("opening_stock");
        assertThat(MovementType.ADJUSTMENT_INVENTORY.needsReason()).isTrue();
        assertThat(MovementType.SALE.needsReason()).isFalse();
    }
}
