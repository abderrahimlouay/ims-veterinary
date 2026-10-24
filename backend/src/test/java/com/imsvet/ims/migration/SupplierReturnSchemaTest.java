package com.imsvet.ims.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.TestLots;
import java.sql.SQLException;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository test (R) of V05_014/015: a return has a non-blank reason and a unique number; a line names a lot of its
 * own product (composite FK, SD-103) with a positive quantity.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SupplierReturnSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    private long supplier;
    private long user;
    private long product;
    private long lot;
    private long movement;

    @BeforeEach
    void data() {
        TestLots lots = new TestLots(jdbc);
        product = lots.product("SRS-1", "Schema return");
        lot = lots.lot(product, "SRS-L1", LocalDate.of(2027, 5, 31), "5");
        movement = jdbc.queryForObject("SELECT id FROM stock_movements WHERE lot_id = ?", Long.class, lot);
        user = lots.fixtureUser();
        jdbc.update("INSERT INTO suppliers (name, created_at, updated_at) VALUES ('Schema SR', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        supplier = jdbc.queryForObject("SELECT id FROM suppliers WHERE name = 'Schema SR'", Long.class);
    }

    @Test
    void aReturnHasAReasonAndAUniqueNumber() {
        checkViolation(catchThrowable(() -> header("SR-TEST-000001", " ")), "ck_supplier_returns_reason_not_blank");
        header("SR-TEST-000001", "Damaged");
        assertThatThrownBy(() -> header("SR-TEST-000001", "Again")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aLineNamesALotOfItsProductWithAPositiveQuantity() {
        long header = header("SR-TEST-000002", "Damaged");
        long other = new TestLots(jdbc).product("SRS-2", "Other");

        assertThatThrownBy(() -> line(header, other, "1")).isInstanceOf(DataIntegrityViolationException.class);
        checkViolation(catchThrowable(() -> line(header, product, "0")), "ck_supplier_return_lines_quantity");
        line(header, product, "1");
    }

    private long header(String number, String reason) {
        jdbc.update("""
                INSERT INTO supplier_returns (number, supplier_id, reason, created_by, created_at)
                VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6))""", number, supplier, reason, user);
        return jdbc.queryForObject("SELECT id FROM supplier_returns WHERE number = ?", Long.class, number);
    }

    private void line(long header, long productId, String quantity) {
        jdbc.update("""
                INSERT INTO supplier_return_lines (return_id, lot_id, product_id, quantity_base, movement_id)
                VALUES (?, ?, ?, ?, ?)""", header, lot, productId, quantity, movement);
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
