package com.imsvet.ims.support;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Inserts products and lots directly: Phase 3 offers no way to create a lot (ADR-064), so lot tests start from
 * rows written here. Production code never creates lots this way.
 */
public final class TestLots {

    private final JdbcTemplate jdbc;

    public TestLots(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long product(String sku, String name) {
        jdbc.update("""
                INSERT INTO products (sku, commercial_name, base_unit, is_divisible, vat_rate, created_at, updated_at)
                VALUES (?, ?, 'tablet', FALSE, 9.00, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", sku, name);
        return jdbc.queryForObject("SELECT id FROM products WHERE sku = ?", Long.class, sku);
    }

    public long lot(long productId, String lotNumber, LocalDate expiry, String quantity) {
        return lot(productId, lotNumber, expiry, quantity, "active");
    }

    public long lot(long productId, String lotNumber, LocalDate expiry, String quantity, String status) {
        jdbc.update("""
                INSERT INTO lots (product_id, lot_number, expiry_date, unit_cost, quantity_on_hand, status, received_at)
                VALUES (?, ?, ?, 0.5, ?, ?, '2026-10-01 08:00:00')""",
                productId, lotNumber, expiry, new BigDecimal(quantity), status);
        return jdbc.queryForObject("SELECT id FROM lots WHERE product_id = ? AND lot_number = ?", Long.class,
                productId, lotNumber);
    }
}
