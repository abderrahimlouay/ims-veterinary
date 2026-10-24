package com.imsvet.ims.purchasing.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * BR-PURCH-001: {@code draft → validated → sent → partially_received → closed}; {@code cancelled} from draft,
 * validated or sent; a forced close from partially received (ADR-096). Receipts move sent and partially received
 * orders ({@link #receivable()}): partially received, then closed once everything is received (BR-PURCH-004).
 */
public enum OrderStatus {
    DRAFT, VALIDATED, SENT, PARTIALLY_RECEIVED, CLOSED, CANCELLED;

    public enum Action {
        VALIDATE, SEND, CANCEL, CLOSE
    }

    /** The status after {@code action}, or empty when the transition is not allowed. */
    public Optional<OrderStatus> after(Action action) {
        return Optional.ofNullable(switch (action) {
            case VALIDATE -> this == DRAFT ? VALIDATED : null;
            case SEND -> this == VALIDATED ? SENT : null;
            case CANCEL -> this == DRAFT || this == VALIDATED || this == SENT ? CANCELLED : null;
            case CLOSE -> this == PARTIALLY_RECEIVED ? CLOSED : null;
        });
    }

    /** Only sent and partially received orders can be received (ADR-096). */
    public boolean receivable() {
        return this == SENT || this == PARTIALLY_RECEIVED;
    }

    /** The stored and API value. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<OrderStatus> fromCode(String code) {
        return Arrays.stream(values()).filter(s -> s.code().equals(code)).findFirst();
    }
}
