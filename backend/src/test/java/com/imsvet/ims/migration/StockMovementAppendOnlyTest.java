package com.imsvet.ims.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.MigrationAccount;
import com.imsvet.ims.support.TestLots;
import java.sql.SQLException;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * BR-STOCK-003 at the database: the triggers of ADR-017 refuse to change or remove a movement even to the migration
 * account, which holds every right on the schema. The movements must be committed for the trigger to see them, and
 * can never be deleted, hence the separate database ({@link CommittedDataDatabase}).
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class StockMovementAppendOnlyTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MigrationAccount migration;

    private long lot;

    @BeforeEach
    void committedMovement() {
        TestLots lots = new TestLots(jdbc);
        String suffix = Long.toString(System.nanoTime());
        lot = lots.lot(lots.product("APPEND-" + suffix, "Append only"), "L-" + suffix, LocalDate.of(2027, 5, 31), "10");
    }

    @Test
    void updateIsRefusedEvenToThePrivilegedAccount() {
        assertThatThrownBy(() -> migration.jdbc().update("UPDATE stock_movements SET quantity = 999 WHERE lot_id = ?", lot))
                .satisfies(StockMovementAppendOnlyTest::isAppendOnlySignal);

        assertThat(jdbc.queryForObject("SELECT quantity FROM stock_movements WHERE lot_id = ?", String.class, lot))
                .isEqualTo("10.000");
    }

    @Test
    void deleteIsRefusedEvenToThePrivilegedAccount() {
        assertThatThrownBy(() -> migration.jdbc().update("DELETE FROM stock_movements WHERE lot_id = ?", lot))
                .satisfies(StockMovementAppendOnlyTest::isAppendOnlySignal);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE lot_id = ?", Integer.class, lot))
                .isEqualTo(1);
    }

    private static void isAppendOnlySignal(Throwable thrown) {
        assertThat(((DataAccessException) thrown).getMostSpecificCause())
                .isInstanceOfSatisfying(SQLException.class, sql -> {
                    assertThat(sql.getSQLState()).isEqualTo("45000");
                    assertThat(sql.getMessage()).contains("append-only");
                });
    }
}
