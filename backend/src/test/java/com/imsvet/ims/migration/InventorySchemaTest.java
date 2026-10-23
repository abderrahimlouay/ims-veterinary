package com.imsvet.ims.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.TestLots;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository test (R) of the count tables: one open full count at most (BR-INV-007, generated unique flag); a line
 * without lot carries a found lot (BR-LOT-001, ADR-074); counted quantity and balance at count go together (ADR-072).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class InventorySchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void onlyOneFullCountMayBeOpen() {
        long user = new TestLots(jdbc).fixtureUser();
        insertCount("full", "open", user);
        insertCount("full", "closed", user);
        insertCount("cycle", "open", user);

        assertThatThrownBy(() -> insertCount("full", "open", user)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void aLineWithoutLotNeedsTheFoundLotAndCountedGoesWithItsBalance() {
        TestLots lots = new TestLots(jdbc);
        long user = lots.fixtureUser();
        long product = lots.product("INV-SCHEMA", "Schema");
        long count = insertCount("cycle", "open", user);

        checkViolation(catchThrowable(() -> jdbc.update(
                "INSERT INTO inventory_lines (inventory_id, product_id) VALUES (?, ?)", count, product)),
                "ck_inventory_lines_found_lot");
        checkViolation(catchThrowable(() -> jdbc.update("""
                INSERT INTO inventory_lines (inventory_id, product_id, found_lot_number, found_expiry_date,
                                             found_unit_cost, counted_qty)
                VALUES (?, ?, 'F-1', '2027-01-31', 0, 3)""", count, product)),
                "ck_inventory_lines_counted_together");
    }

    /** MySQL reports a violated CHECK as error 3819 naming the constraint. */
    private static void checkViolation(Throwable thrown, String constraint) {
        assertThat(((DataAccessException) thrown).getMostSpecificCause())
                .isInstanceOfSatisfying(SQLException.class, sql -> {
                    assertThat(sql.getErrorCode()).isEqualTo(3819);
                    assertThat(sql.getMessage()).contains(constraint);
                });
    }

    private long insertCount(String type, String status, long user) {
        jdbc.update("INSERT INTO inventories (inv_type, status, started_by, started_at) VALUES (?, ?, ?, UTC_TIMESTAMP(6))",
                type, status, user);
        return jdbc.queryForObject("SELECT MAX(id) FROM inventories", Long.class);
    }
}
