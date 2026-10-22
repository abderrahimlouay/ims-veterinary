package com.imsvet.ims.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.catalog.domain.UnitConversion;
import com.imsvet.ims.catalog.domain.UnitConversion.InexactConversionException;
import com.imsvet.ims.catalog.domain.UnitConversion.Refusal;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * BR-UNIT-002 and ADR-065: quantity × factor computed exactly with 3 decimals, never rounded; more decimals,
 * a non-positive result or a non-whole result for a non-divisible product are refused.
 */
class UnitConversionTest {

    @Test
    void multipliesExactly() {
        assertThat(UnitConversion.toBase(dec("3"), dec("10"), false)).isEqualTo(dec("30.000"));
        assertThat(UnitConversion.toBase(dec("1.5"), dec("2.5"), true)).isEqualTo(dec("3.750"));
        assertThat(UnitConversion.toBase(dec("0.125"), dec("8"), true)).isEqualTo(dec("1.000"));
    }

    @Test
    void theBaseUnitHasFactorOne() {
        assertThat(UnitConversion.toBase(dec("2.5"), BigDecimal.ONE, true)).isEqualTo(dec("2.500"));
    }

    @Test
    void aResultNeedingMoreThanThreeDecimalsIsRefusedNotRounded() {
        assertRefused(dec("1.25"), dec("1.25"), true, Refusal.SCALE);
    }

    @Test
    void aNonDivisibleProductNeedsWholeBaseQuantities() {
        assertRefused(dec("1.5"), dec("3"), false, Refusal.WHOLE_NUMBER);
        assertThat(UnitConversion.toBase(dec("1.5"), dec("4"), false)).isEqualTo(dec("6.000"));
    }

    @Test
    void theResultMustBePositive() {
        assertRefused(BigDecimal.ZERO, dec("10"), true, Refusal.NOT_POSITIVE);
        assertRefused(dec("-1"), dec("10"), true, Refusal.NOT_POSITIVE);
    }

    private static void assertRefused(BigDecimal quantity, BigDecimal factor, boolean divisible, Refusal refusal) {
        assertThatThrownBy(() -> UnitConversion.toBase(quantity, factor, divisible))
                .isInstanceOfSatisfying(InexactConversionException.class, e -> assertThat(e.refusal()).isEqualTo(refusal));
    }

    private static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }
}
