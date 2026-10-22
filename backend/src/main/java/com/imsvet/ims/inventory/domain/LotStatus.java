package com.imsvet.ims.inventory.domain;

import java.util.Arrays;
import java.util.Locale;

/**
 * The stored lot status, only what a person decides (ADR-059), and its transition table (BR-LOT-009, ADR-060):
 * active → blocked → active, active or blocked → recalled; recalled is terminal.
 */
public enum LotStatus {
    ACTIVE, BLOCKED, RECALLED;

    public enum Action {
        BLOCK, UNBLOCK, RECALL
    }

    /** A request that the transition table does not allow ({@code INVALID_STATE_TRANSITION}). */
    public static final class IllegalLotTransitionException extends RuntimeException {

        IllegalLotTransitionException(LotStatus from, Action action) {
            super("Cannot " + action + " a lot that is " + from.code());
        }
    }

    public LotStatus after(Action action) {
        LotStatus next = switch (action) {
            case BLOCK -> this == ACTIVE ? BLOCKED : null;
            case UNBLOCK -> this == BLOCKED ? ACTIVE : null;
            case RECALL -> this == RECALLED ? null : RECALLED;
        };
        if (next == null) {
            throw new IllegalLotTransitionException(this, action);
        }
        return next;
    }

    /** The stored and API value. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static LotStatus fromCode(String code) {
        return Arrays.stream(values()).filter(s -> s.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown lot status " + code));
    }
}
