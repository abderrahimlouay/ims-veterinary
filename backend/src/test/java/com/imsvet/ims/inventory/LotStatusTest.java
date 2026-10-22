package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.inventory.domain.EffectiveLotStatus;
import com.imsvet.ims.inventory.domain.LotStatus;
import com.imsvet.ims.inventory.domain.LotStatus.Action;
import com.imsvet.ims.inventory.domain.LotStatus.IllegalLotTransitionException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * BR-LOT-009, ADR-059 and ADR-060: the stored-status transition table (recalled is terminal) and the effective
 * status, first that applies of recalled, blocked, expired, depleted, active.
 */
class LotStatusTest {

    @ParameterizedTest
    @CsvSource({ "ACTIVE, BLOCK, BLOCKED", "BLOCKED, UNBLOCK, ACTIVE", "ACTIVE, RECALL, RECALLED",
            "BLOCKED, RECALL, RECALLED" })
    void allowedTransitions(LotStatus from, Action action, LotStatus to) {
        assertThat(from.after(action)).isEqualTo(to);
    }

    @ParameterizedTest
    @CsvSource({ "ACTIVE, UNBLOCK", "BLOCKED, BLOCK", "RECALLED, BLOCK", "RECALLED, UNBLOCK", "RECALLED, RECALL" })
    void everyOtherTransitionIsRefused(LotStatus from, Action action) {
        assertThatThrownBy(() -> from.after(action)).isInstanceOf(IllegalLotTransitionException.class);
    }

    @Test
    void storedStatusesUseLowerCaseCodes() {
        assertThat(LotStatus.fromCode("recalled")).isEqualTo(LotStatus.RECALLED);
        assertThat(LotStatus.BLOCKED.code()).isEqualTo("blocked");
    }

    @Test
    void effectiveStatusFollowsThePriority() {
        BigDecimal some = new BigDecimal("5.000");
        BigDecimal none = BigDecimal.ZERO.setScale(3);

        assertThat(EffectiveLotStatus.of(LotStatus.RECALLED, true, none)).isEqualTo(EffectiveLotStatus.RECALLED);
        assertThat(EffectiveLotStatus.of(LotStatus.BLOCKED, true, none)).isEqualTo(EffectiveLotStatus.BLOCKED);
        assertThat(EffectiveLotStatus.of(LotStatus.ACTIVE, true, none)).isEqualTo(EffectiveLotStatus.EXPIRED);
        assertThat(EffectiveLotStatus.of(LotStatus.ACTIVE, false, none)).isEqualTo(EffectiveLotStatus.DEPLETED);
        assertThat(EffectiveLotStatus.of(LotStatus.ACTIVE, false, some)).isEqualTo(EffectiveLotStatus.ACTIVE);
    }
}
