package com.imsvet.ims.support;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Inserts products and lots directly, for tests that start from existing stock. A lot with stock also gets the
 * {@code opening_stock} movement that explains its balance, so ledger = balance holds in every test (BR-STOCK-007).
 * Production code creates lots only through its use cases.
 */
public final class TestLots {

    private final JdbcTemplate jdbc;
    private Long fixtureUser;

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
        BigDecimal balance = new BigDecimal(quantity);
        jdbc.update("""
                INSERT INTO lots (product_id, lot_number, expiry_date, unit_cost, quantity_on_hand, status, received_at)
                VALUES (?, ?, ?, 0.5, ?, ?, '2026-10-01 08:00:00')""",
                productId, lotNumber, expiry, balance, status);
        long id = jdbc.queryForObject("SELECT id FROM lots WHERE product_id = ? AND lot_number = ?", Long.class,
                productId, lotNumber);
        if (balance.signum() > 0) {
            jdbc.update("""
                    INSERT INTO stock_movements (lot_id, product_id, movement_type, quantity, user_id, created_at)
                    VALUES (?, ?, 'opening_stock', ?, ?, '2026-10-01 08:00:00')""", id, productId, balance, fixtureUser());
        }
        return id;
    }

    /** The operator recorded on fixture movements (BR-STOCK-004: every movement is attributable). */
    public long fixtureUser() {
        if (fixtureUser == null) {
            String username = "fixture-" + UUID.randomUUID().toString().substring(0, 12);
            jdbc.update("""
                    INSERT INTO users (username, password_hash, full_name, role_id, is_active, must_change_password,
                                       created_at)
                    SELECT ?, ?, 'Lot fixture', id, TRUE, FALSE, UTC_TIMESTAMP(6) FROM roles WHERE name = 'Pharmacist'""",
                    username, "not-a-login-" + UUID.randomUUID());
            fixtureUser = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
        }
        return fixtureUser;
    }
}
