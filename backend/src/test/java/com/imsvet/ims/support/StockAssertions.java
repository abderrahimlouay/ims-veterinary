package com.imsvet.ims.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/** BR-STOCK-007 in every stock test: each lot's balance equals the sum of its movements (ADR-028). */
public final class StockAssertions {

    private StockAssertions() {
    }

    public static void assertLedgerMatchesBalances(JdbcTemplate jdbc, Collection<Long> lotIds) {
        if (lotIds.isEmpty()) {
            return;
        }
        String ids = lotIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        List<Map<String, Object>> drift = jdbc.queryForList("""
                SELECT l.id, l.quantity_on_hand, COALESCE(SUM(m.quantity), 0) AS ledger
                FROM lots l LEFT JOIN stock_movements m ON m.lot_id = l.id
                WHERE l.id IN (%s) GROUP BY l.id, l.quantity_on_hand
                HAVING l.quantity_on_hand <> COALESCE(SUM(m.quantity), 0)""".formatted(ids));
        assertThat(drift).as("lots whose balance differs from their ledger").isEmpty();
    }
}
