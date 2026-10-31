package com.imsvet.ims.finance;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.finance.domain.CashAmounts;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** BR-CASH-002/003. */
class CashAmountsTest {

    @Test
    void expectedIsOpeningPlusCashInMinusCashOut() {
        assertThat(CashAmounts.expected(new BigDecimal("100.00"), new BigDecimal("45.50"), new BigDecimal("20.00")))
                .isEqualByComparingTo("125.50");
    }

    @Test
    void varianceIsCountedMinusExpected() {
        assertThat(CashAmounts.variance(new BigDecimal("120.00"), new BigDecimal("125.50")))
                .isEqualByComparingTo("-5.50");
    }
}
