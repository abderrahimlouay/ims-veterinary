package com.imsvet.ims.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.TestLots;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository test (R) of V05_005/006: status and cancellation CHECKs, one line per product, the unit of the line's
 * product only (composite FK), received never above ordered (ADR-096), generated line amounts (ADR-093).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PurchaseOrderSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    private long order;
    private long product;
    private long other;

    @BeforeEach
    void order() {
        TestLots lots = new TestLots(jdbc);
        product = lots.product("POS-1", "Schema order");
        other = lots.product("POS-2", "Other");
        long user = lots.fixtureUser();
        jdbc.update("INSERT INTO suppliers (name, created_at, updated_at) VALUES ('Schema supplier', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        long supplier = jdbc.queryForObject("SELECT id FROM suppliers WHERE name = 'Schema supplier'", Long.class);
        jdbc.update("""
                INSERT INTO purchase_orders (number, supplier_id, order_date, created_by, created_at, updated_at)
                VALUES ('PO-TEST-000001', ?, '2026-10-20', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", supplier, user);
        order = jdbc.queryForObject("SELECT id FROM purchase_orders WHERE number = 'PO-TEST-000001'", Long.class);
    }

    @Test
    void amountsAreGeneratedPerLine() {
        line(product, null, "3", "3", "0.3350", "0", "9");

        assertThat(jdbc.queryForObject("SELECT CONCAT(line_ht, '/', line_vat) FROM purchase_order_lines WHERE purchase_order_id = ?",
                String.class, order)).isEqualTo("1.01/0.09");
    }

    @Test
    void oneLinePerProductAndReceivedNeverAboveOrdered() {
        line(product, null, "3", "3", "1", "0", "9");
        assertThatThrownBy(() -> line(product, null, "1", "1", "1", "0", "9")).isInstanceOf(DuplicateKeyException.class);
        checkViolation(catchThrowable(() -> jdbc.update(
                "UPDATE purchase_order_lines SET received_base = 4 WHERE purchase_order_id = ?", order)),
                "ck_purchase_order_lines_received");
    }

    @Test
    void theUnitBelongsToTheLineProduct() {
        jdbc.update("INSERT INTO product_units (product_id, name, factor_to_base) VALUES (?, 'Box', 10)", other);
        long box = jdbc.queryForObject("SELECT id FROM product_units WHERE product_id = ?", Long.class, other);

        assertThatThrownBy(() -> line(product, box, "1", "10", "1", "0", "9"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aCancelledOrderHasItsReasonAndDate() {
        checkViolation(catchThrowable(() -> jdbc.update("UPDATE purchase_orders SET status = 'cancelled' WHERE id = ?",
                order)), "ck_purchase_orders_cancelled");
        checkViolation(catchThrowable(() -> jdbc.update("UPDATE purchase_orders SET status = 'shipped' WHERE id = ?",
                order)), "ck_purchase_orders_status");
    }

    private void line(long productId, Long unit, String quantity, String base, String price, String discount, String vat) {
        jdbc.update("""
                INSERT INTO purchase_order_lines (purchase_order_id, product_id, unit_id, quantity, quantity_base,
                                                  unit_price, discount_pct, vat_rate)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", order, productId, unit, quantity, base, price, discount, vat);
    }

    /** MySQL reports a violated CHECK as error 3819 naming the constraint. */
    private static void checkViolation(Throwable thrown, String constraint) {
        assertThat(((DataAccessException) thrown).getMostSpecificCause())
                .isInstanceOfSatisfying(SQLException.class, sql -> {
                    assertThat(sql.getErrorCode()).isEqualTo(3819);
                    assertThat(sql.getMessage()).contains(constraint);
                });
    }
}
