package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.sales.domain.WithdrawalWarnings;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR-121: the line's species, every species when none is given, nothing for a period without days. */
class WithdrawalWarningsTest {

    private record P(int species, Integer days) {
    }

    private static final List<P> PERIODS = List.of(new P(1, 28), new P(2, 14), new P(3, null));

    @Test
    void selectsTheSpeciesOrAllOfThem() {
        assertThat(select(2)).containsExactly(new P(2, 14));
        assertThat(select(null)).containsExactly(new P(1, 28), new P(2, 14));
        assertThat(select(3)).isEmpty();
        assertThat(select(9)).isEmpty();
    }

    private static List<P> select(Integer species) {
        return WithdrawalWarnings.select(PERIODS, species, P::species, p -> p.days() != null);
    }
}
