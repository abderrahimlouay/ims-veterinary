package com.imsvet.ims.inventory.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The cost of a lot when stock enters it (ADR-098): the weighted average of the current balance at the lot cost
 * and the entry at its cost, 4 decimals HALF_UP; on an empty lot the entry cost replaces the old one (no division
 * by zero).
 */
public final class LotCost {

    private static final int COST_SCALE = 4;

    private LotCost() {
    }

    public static BigDecimal weightedAverage(BigDecimal balance, BigDecimal cost, BigDecimal quantity,
            BigDecimal entryCost) {
        if (balance.signum() == 0) {
            return entryCost.setScale(COST_SCALE, RoundingMode.HALF_UP);
        }
        return balance.multiply(cost).add(quantity.multiply(entryCost))
                .divide(balance.add(quantity), COST_SCALE, RoundingMode.HALF_UP);
    }
}
