package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.inventory.application.LotActions;
import com.imsvet.ims.support.MigrationAccount;
import com.imsvet.ims.support.TestLots;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * ADR-012, BR-STOCK-012: a stock use case waits at most 5 s for a lot row held by another transaction, then gives
 * up with a concurrency failure ({@code CONCURRENT_MODIFICATION} at the API) instead of hanging; nothing changes.
 * Runs on committed data, without a test transaction, in the database for committed data.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class LockWaitTimeoutTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MigrationAccount migration;

    @Autowired
    private LotActions actions;

    @Autowired
    private TestClock clock;

    private long product;
    private long lot;

    @BeforeEach
    void committedLot() {
        clock.reset();
        TestLots lots = new TestLots(jdbc);
        String key = UUID.randomUUID().toString().substring(0, 8);
        product = lots.product("LOCK-" + key, "Locked");
        lot = lots.lot(product, "L-" + key, LocalDate.of(2027, 5, 31), "10");
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "pharmacist", null, List.of(new SimpleGrantedAuthority("lot.block"))));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aLockHeldLongerThanFiveSecondsIsAConcurrencyFailure() throws Exception {
        try (Connection holder = migration.jdbc().getDataSource().getConnection()) {
            holder.setAutoCommit(false);
            holder.createStatement().executeQuery("SELECT id FROM lots WHERE id = " + lot + " FOR UPDATE").close();
            long start = System.nanoTime();

            assertThatThrownBy(() -> actions.block(lot, "Damaged")).isInstanceOf(ConcurrencyFailureException.class);

            Duration waited = Duration.ofNanos(System.nanoTime() - start);
            assertThat(waited).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(15));
            holder.rollback();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM lots WHERE id = ?", String.class, lot)).isEqualTo("active");
    }
}
