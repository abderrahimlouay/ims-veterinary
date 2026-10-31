package com.imsvet.ims.finance.application;

import com.imsvet.ims.finance.domain.CashSession;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * api.md §5.12. {@code expectedAmount} is the running amount while open, the stored one once closed;
 * {@code countedAmount} and {@code varianceAmount} are {@code null} while open.
 */
public record CashSessionView(long id, long cashierId, String status, Instant openedAt, Instant closedAt,
        BigDecimal openingAmount, BigDecimal expectedAmount, BigDecimal countedAmount, BigDecimal varianceAmount,
        long version) {

    static CashSessionView of(CashSession s, BigDecimal expected) {
        return new CashSessionView(s.id(), s.cashierId(), s.status().code(), s.openedAt(), s.closedAt(),
                s.openingAmount(), expected, s.countedAmount(), s.varianceAmount(), s.version());
    }
}
