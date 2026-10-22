package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.inventory.domain.AlertLevel;
import com.imsvet.ims.inventory.domain.ExpiryPolicy;
import com.imsvet.ims.inventory.domain.LotStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * BR-LOT-003 (sellable through the expiry date, ADR-055), BR-LOT-010 (inclusive levels from the configured
 * thresholds, ADR-056) and BR-STOCK-011 (sellable = stored active, not expired, quantity > 0).
 */
class ExpiryPolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private static final ExpiryPolicy POLICY = new ExpiryPolicy(TODAY, 60, 30, 15);

    @Test
    void aLotIsSellableThroughItsExpiryDate() {
        assertThat(POLICY.expired(TODAY.minusDays(1))).isTrue();
        assertThat(POLICY.expired(TODAY)).isFalse();
        assertThat(POLICY.expired(TODAY.plusDays(1))).isFalse();
    }

    @ParameterizedTest
    @CsvSource(nullValues = "none", value = { "-1, EXPIRED", "0, CRITICAL", "15, CRITICAL", "16, WARNING", "30, WARNING",
            "31, NOTICE", "60, NOTICE", "61, none" })
    void levelsUseInclusiveThresholds(int daysLeft, AlertLevel level) {
        LocalDate expiry = TODAY.plusDays(daysLeft);

        assertThat(POLICY.daysLeft(expiry)).isEqualTo(daysLeft);
        assertThat(POLICY.level(expiry)).isEqualTo(level);
    }

    @Test
    void otherThresholdsGiveOtherLevels() {
        ExpiryPolicy strict = new ExpiryPolicy(TODAY, 90, 45, 20);

        assertThat(strict.level(TODAY.plusDays(20))).isEqualTo(AlertLevel.CRITICAL);
        assertThat(strict.level(TODAY.plusDays(90))).isEqualTo(AlertLevel.NOTICE);
        assertThat(POLICY.level(TODAY.plusDays(20))).isEqualTo(AlertLevel.WARNING);
    }

    @Test
    void thresholdsAreStrictlyDecreasingPositiveDays() {
        assertThatThrownBy(() -> new ExpiryPolicy(TODAY, 30, 30, 15)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExpiryPolicy(TODAY, 60, 30, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sellableNeedsActiveNotExpiredAndStock() {
        BigDecimal some = new BigDecimal("1.000");

        assertThat(POLICY.sellable(LotStatus.ACTIVE, TODAY, some)).isTrue();
        assertThat(POLICY.sellable(LotStatus.ACTIVE, TODAY.minusDays(1), some)).isFalse();
        assertThat(POLICY.sellable(LotStatus.ACTIVE, TODAY, BigDecimal.ZERO)).isFalse();
        assertThat(POLICY.sellable(LotStatus.BLOCKED, TODAY, some)).isFalse();
        assertThat(POLICY.sellable(LotStatus.RECALLED, TODAY, some)).isFalse();
    }
}
