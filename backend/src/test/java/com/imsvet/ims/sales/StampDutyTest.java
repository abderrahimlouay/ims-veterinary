package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.sales.domain.SalesDocType;
import com.imsvet.ims.sales.domain.StampDuty;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** ADR-172: the stamp-duty scale on the cash part; every figure comes from the settings. */
class StampDutyTest {

    /** 1 % per started 100.00, between 5.00 and 2,500.00, nothing up to 300.00. */
    private static final StampDuty SCALE = new StampDuty(new BigDecimal("1.00"), new BigDecimal("100.00"),
            new BigDecimal("5.00"), new BigDecimal("2500.00"), new BigDecimal("300.00"), true);

    @Test
    void eachStartedStepCountsAndTheRateApplies() {
        assertThat(SCALE.on(new BigDecimal("2180.00"))).isEqualByComparingTo("22.00");
        assertThat(SCALE.on(new BigDecimal("2100.00"))).isEqualByComparingTo("21.00");
        assertThat(SCALE.on(new BigDecimal("2100.01"))).isEqualByComparingTo("22.00");
        assertThat(SCALE.on(new BigDecimal("2180.00")).scale()).isEqualTo(2);
    }

    @Test
    void nothingAtOrBelowTheThresholdOrWithoutCash() {
        assertThat(SCALE.on(new BigDecimal("300.00"))).isEqualByComparingTo("0");
        assertThat(SCALE.on(BigDecimal.ZERO)).isEqualByComparingTo("0");
        assertThat(SCALE.on(new BigDecimal("300.01"))).isEqualByComparingTo("5.00");
    }

    @Test
    void theMinimumAndTheMaximumBoundTheAmount() {
        assertThat(SCALE.on(new BigDecimal("350.00"))).isEqualByComparingTo("5.00");
        assertThat(SCALE.on(new BigDecimal("10000000.00"))).isEqualByComparingTo("2500.00");
        StampDuty noMaximum = new StampDuty(new BigDecimal("1.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null,
                BigDecimal.ZERO, true);
        assertThat(noMaximum.on(new BigDecimal("10000000.00"))).isEqualByComparingTo("100000.00");
        StampDuty crossed = new StampDuty(new BigDecimal("1.00"), new BigDecimal("0.01"), new BigDecimal("50.00"),
                new BigDecimal("20.00"), BigDecimal.ZERO, true);
        assertThat(crossed.on(new BigDecimal("100.00"))).as("the maximum wins").isEqualByComparingTo("20.00");
    }

    @Test
    void aPlainRateRoundsHalfUpToTheCent() {
        StampDuty plain = new StampDuty(new BigDecimal("1.00"), new BigDecimal("0.01"), BigDecimal.ZERO, null,
                BigDecimal.ZERO, true);
        assertThat(plain.on(new BigDecimal("1234.50"))).isEqualByComparingTo("12.35");
        assertThat(plain.on(new BigDecimal("1234.49"))).isEqualByComparingTo("12.34");
    }

    @Test
    void onlyImmediateInvoicesAndReceiptsCarryIt() {
        assertThat(SCALE.appliesTo(SalesDocType.INVOICE, false)).isTrue();
        assertThat(SCALE.appliesTo(SalesDocType.RECEIPT, false)).isTrue();
        assertThat(SCALE.appliesTo(SalesDocType.INVOICE, true)).isFalse();
        assertThat(SCALE.appliesTo(SalesDocType.DELIVERY_NOTE, false)).isFalse();
        assertThat(SCALE.appliesTo(SalesDocType.QUOTE, false)).isFalse();
        assertThat(SCALE.appliesTo(SalesDocType.CREDIT_NOTE, false)).isFalse();
        StampDuty invoicesOnly = new StampDuty(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, null, BigDecimal.ZERO,
                false);
        assertThat(invoicesOnly.appliesTo(SalesDocType.RECEIPT, false)).isFalse();
        assertThat(invoicesOnly.appliesTo(SalesDocType.INVOICE, false)).isTrue();
    }

    @Test
    void aScaleWithoutARateOrStepIsRefused() {
        assertThatThrownBy(() -> new StampDuty(BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, null, BigDecimal.ZERO,
                true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StampDuty(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO,
                true)).isInstanceOf(IllegalArgumentException.class);
    }
}
