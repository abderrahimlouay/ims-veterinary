package com.imsvet.ims.sales.domain;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * ADR-121: which withdrawal periods a sales line warns about, for any customer: those of the line's target species,
 * or every species of the product when none is given. A period without any days is no data and warns about nothing.
 */
public final class WithdrawalWarnings {

    private WithdrawalWarnings() {
    }

    public static <P> List<P> select(List<P> periods, Integer targetSpeciesId, ToIntFunction<P> species,
            Predicate<P> hasDays) {
        return periods.stream().filter(hasDays)
                .filter(p -> targetSpeciesId == null || species.applyAsInt(p) == targetSpeciesId)
                .toList();
    }
}
