package com.imsvet.ims.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.TestLots;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository test (R) of stock_movements (database.md §3.4): the ten types of ADR-081 and no other, the sign per
 * type (BR-STOCK-005), no zero quantity, a reason for every adjustment (BR-STOCK-006), the movement's product equal
 * to its lot's (ADR-062), an operator on every row (BR-STOCK-004), and no UPDATE/DELETE for the application account
 * (1142, ADR-087). The trigger side is {@code StockMovementAppendOnlyTest}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class StockMovementSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    private TestLots lots;
    private long product;
    private long lot;

    @BeforeEach
    void lot() {
        lots = new TestLots(jdbc);
        product = lots.product("MOV-SCHEMA", "Amoxival");
        lot = lots.lot(product, "L-MOV", LocalDate.of(2027, 5, 31), "100");
    }

    @ParameterizedTest
    @CsvSource({ "purchase_receipt, 5", "customer_return, 1", "opening_stock, 2", "sale, -1", "supplier_return, -1",
            "adjustment_breakage, -1", "adjustment_expiry, -1", "adjustment_theft, -1", "adjustment_internal_use, -1",
            "adjustment_inventory, 3", "adjustment_inventory, -3", "receipt_cancellation, -1" })
    void theElevenTypesWithTheirSignAreAccepted(String type, String quantity) {
        insert(type, quantity, "Reason");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE lot_id = ? AND movement_type = ?",
                Integer.class, lot, type)).isPositive();
    }

    @ParameterizedTest
    @ValueSource(strings = { "unit_conversion", "sale_cancellation", "discard" })
    void otherTypesAreRefused(String type) {
        // An unknown type also fails the sign CHECK, which MySQL may report first: either way the row is refused.
        assertThatThrownBy(() -> insert(type, "1", "Reason")).satisfies(e -> checkViolation(e, "ck_stock_movements_"));
    }

    @ParameterizedTest
    @CsvSource({ "opening_stock, -1", "purchase_receipt, -2", "sale, 1", "adjustment_breakage, 1",
            "receipt_cancellation, 1" })
    void aWrongSignIsRefused(String type, String quantity) {
        assertThatThrownBy(() -> insert(type, quantity, "Reason")).satisfies(e -> checkViolation(e, "ck_stock_movements_sign"));
    }

    @Test
    void aZeroQuantityIsRefused() {
        assertThatThrownBy(() -> insert("adjustment_inventory", "0", "Reason"))
                .satisfies(e -> checkViolation(e, "ck_stock_movements_quantity_not_zero"));
    }

    @Test
    void everyAdjustmentNeedsAReason() {
        assertThatThrownBy(() -> insert("adjustment_theft", "-1", null))
                .satisfies(e -> checkViolation(e, "ck_stock_movements_adjustment_reason"));
        assertThatThrownBy(() -> insert("adjustment_inventory", "1", "   "))
                .satisfies(e -> checkViolation(e, "ck_stock_movements_adjustment_reason"));
        insert("sale", "-1", null);
    }

    @Test
    void theMovementProductMustBeTheLotProduct() {
        long other = lots.product("MOV-OTHER", "Other");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO stock_movements (lot_id, product_id, movement_type, quantity, user_id, created_at)
                VALUES (?, ?, 'opening_stock', 1, ?, UTC_TIMESTAMP(6))""", lot, other, lots.fixtureUser()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void everyMovementHasAnOperator() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO stock_movements (lot_id, product_id, movement_type, quantity, user_id, created_at)
                VALUES (?, ?, 'opening_stock', 1, NULL, UTC_TIMESTAMP(6))""", lot, product))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theApplicationAccountCanNeitherChangeNorRemoveMovements() {
        assertDenied(() -> jdbc.update("UPDATE stock_movements SET quantity = 999 WHERE lot_id = ?", lot));
        assertDenied(() -> jdbc.update("DELETE FROM stock_movements WHERE lot_id = ?", lot));
    }

    private void insert(String type, String quantity, String reason) {
        jdbc.update("""
                INSERT INTO stock_movements (lot_id, product_id, movement_type, quantity, reason, user_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))""",
                lot, product, type, new BigDecimal(quantity), reason, lots.fixtureUser());
    }

    /** MySQL reports a violated CHECK as error 3819 naming the constraint. */
    private static void checkViolation(Throwable thrown, String constraint) {
        assertThat(((DataAccessException) thrown).getMostSpecificCause())
                .isInstanceOfSatisfying(SQLException.class, sql -> {
                    assertThat(sql.getErrorCode()).isEqualTo(3819);
                    assertThat(sql.getMessage()).contains(constraint);
                });
    }

    /** MySQL error 1142: command denied to user for table. */
    private static void assertDenied(Runnable statement) {
        assertThatThrownBy(statement::run).isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(((DataAccessException) e).getMostSpecificCause())
                        .isInstanceOfSatisfying(SQLException.class, sql -> assertThat(sql.getErrorCode()).isEqualTo(1142)));
    }
}
