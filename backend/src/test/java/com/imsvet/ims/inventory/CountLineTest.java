package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.inventory.domain.CountLine;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** BR-INV-001, ADR-072: variance = counted − balance at the watermark, not the theoretical quantity. */
class CountLineTest {

    private static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");

    @Test
    void theVarianceIsTakenAgainstTheBalanceAtCount() {
        CountLine line = CountLine.ofLot(1L, 2L, 3L, new BigDecimal("10.000"));
        assertThat(line.varianceQty()).isNull();

        line.count(new BigDecimal("7.000"), new BigDecimal("8.000"), 40L, 5L, NOW);
        assertThat(line.varianceQty()).isEqualByComparingTo("-1");

        line.count(new BigDecimal("9.000"), new BigDecimal("8.000"), 41L, 5L, NOW);
        assertThat(line.varianceQty()).isEqualByComparingTo("1");
        assertThat(line.watermarkMovementId()).isEqualTo(41L);

        line.count(new BigDecimal("8.000"), new BigDecimal("8.000"), 41L, 5L, NOW);
        assertThat(line.varianceQty()).isEqualByComparingTo("0");
    }

    @Test
    void aRegularisedLineIsNeverCountedAgain() {
        CountLine line = CountLine.ofLot(1L, 2L, 3L, new BigDecimal("10.000"));
        ReflectionTestUtils.setField(line, "regularized", true);

        assertThatThrownBy(() -> line.count(BigDecimal.ONE, BigDecimal.ONE, 1L, 5L, NOW))
                .isInstanceOf(IllegalStateException.class);
    }
}
