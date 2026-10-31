package com.imsvet.ims.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.MigrationAccount;
import com.imsvet.ims.support.TestLots;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** BR-PAY-003, SD-102: the triggers refuse to change or remove a cash movement, even to the migration account. */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class CashMovementAppendOnlyTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MigrationAccount migration;

    private long session;

    @BeforeEach
    void committedMovement() {
        long user = new TestLots(jdbc).fixtureUser();
        jdbc.update("UPDATE cash_sessions SET status = 'closed' WHERE cashier_id = ?", user);
        jdbc.update("INSERT INTO cash_sessions (cashier_id, opened_at) VALUES (?, NOW(6))", user);
        session = jdbc.queryForObject("SELECT MAX(id) FROM cash_sessions WHERE cashier_id = ?", Long.class, user);
        jdbc.update("INSERT INTO cash_movements (cash_session_id, direction, method, amount, created_by, created_at)"
                + " VALUES (?, 'in', 'cash', 10, ?, NOW(6))", session, user);
    }

    @Test
    void updateIsRefusedEvenToThePrivilegedAccount() {
        assertThatThrownBy(() -> migration.jdbc().update(
                "UPDATE cash_movements SET amount = 999 WHERE cash_session_id = ?", session))
                .satisfies(CashMovementAppendOnlyTest::isAppendOnlySignal);
    }

    @Test
    void deleteIsRefusedEvenToThePrivilegedAccount() {
        assertThatThrownBy(() -> migration.jdbc().update("DELETE FROM cash_movements WHERE cash_session_id = ?", session))
                .satisfies(CashMovementAppendOnlyTest::isAppendOnlySignal);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_movements WHERE cash_session_id = ?", Integer.class,
                session)).isOne();
    }

    private static void isAppendOnlySignal(Throwable thrown) {
        assertThat(((DataAccessException) thrown).getMostSpecificCause())
                .isInstanceOfSatisfying(SQLException.class, sql -> {
                    assertThat(sql.getSQLState()).isEqualTo("45000");
                    assertThat(sql.getMessage()).contains("append-only");
                });
    }
}
