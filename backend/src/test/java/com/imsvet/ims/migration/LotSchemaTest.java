package com.imsvet.ims.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.TestLots;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository test (R) of lots (database.md §3.4): identity (product, lot number) ignoring case, whatever the
 * expiry (ADR-070, ADR-058); no negative stock (BR-STOCK-001); only decided statuses are stored (BR-LOT-009,
 * ADR-059); UQ(id, product_id) and the FEFO and expiry indexes exist (ADR-062, SD-009).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class LotSchemaTest {

    private static final LocalDate EXPIRY = LocalDate.of(2027, 10, 31);

    @Autowired
    private JdbcTemplate jdbc;

    private TestLots lots;
    private long product;

    @BeforeEach
    void product() {
        lots = new TestLots(jdbc);
        product = lots.product("LOT-SCHEMA", "Amoxival");
    }

    @Test
    void aLotNumberIsUniquePerProductIgnoringCaseEvenWithAnotherExpiry() {
        lots.lot(product, "L2407a", EXPIRY, "10");

        assertThatThrownBy(() -> lots.lot(product, "L2407A", EXPIRY, "5")).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> lots.lot(product, "L2407a", EXPIRY.plusMonths(1), "5"))
                .isInstanceOf(DuplicateKeyException.class);
        lots.lot(lots.product("LOT-OTHER", "Other"), "L2407A", EXPIRY, "5");
    }

    @Test
    void stockOnHandCannotBeNegative() {
        assertThatThrownBy(() -> lots.lot(product, "NEG", EXPIRY, "-0.001"))
                .satisfies(e -> checkViolation(e, "quantity_on_hand"));
    }

    @Test
    void expiredAndDepletedAreNeverStored() {
        assertThatThrownBy(() -> lots.lot(product, "E1", EXPIRY, "1", "expired")).satisfies(e -> checkViolation(e, "status"));
        assertThatThrownBy(() -> lots.lot(product, "D1", EXPIRY, "0", "depleted")).satisfies(e -> checkViolation(e, "status"));
        lots.lot(product, "B1", EXPIRY, "1", "blocked");
        lots.lot(product, "R1", EXPIRY, "1", "recalled");
    }

    @Test
    void aLotNumberIsNotBlank() {
        assertThatThrownBy(() -> lots.lot(product, " ", EXPIRY, "1")).satisfies(e -> checkViolation(e, "lot_number"));
    }

    @Test
    void compositeKeyAndIndexesExist() {
        List<String> constraints = jdbc.queryForList("""
                SELECT constraint_name FROM information_schema.table_constraints
                WHERE table_schema = DATABASE() AND table_name = 'lots' AND constraint_type = 'UNIQUE'""", String.class);
        List<String> indexes = jdbc.queryForList("""
                SELECT DISTINCT index_name FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'lots'""", String.class);

        assertThat(constraints).contains("uq_lots_product_lot_number", "uq_lots_id_product");
        assertThat(indexes).contains("idx_lots_fefo", "idx_lots_expiry");
    }

    /** MySQL reports a violated CHECK as error 3819 naming the constraint. */
    private static void checkViolation(Throwable thrown, String column) {
        assertThat(((DataAccessException) thrown).getMostSpecificCause())
                .isInstanceOfSatisfying(SQLException.class, sql -> {
                    assertThat(sql.getErrorCode()).isEqualTo(3819);
                    assertThat(sql.getMessage()).contains(column);
                });
    }
}
