package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.inventory.application.ReorderJob;
import com.imsvet.ims.support.TestLots;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-078: the daily generation runs once per business day, at the first tick at or after {@code reorder_job_time}
 * (default 06:00 Africa/Algiers = 05:00 UTC), the system recorded as trigger. Ticks are called directly.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ReorderJobTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestClock clock;

    @Autowired
    private ReorderJob job;

    @BeforeEach
    void before() {
        clock.reset();
    }

    @Test
    void runsOnceAtTheConfiguredBusinessTime() {
        TestLots lots = new TestLots(jdbc);
        long product = lots.product("RO-JOB", "Job");
        jdbc.update("UPDATE products SET reorder_point = 10, max_stock = 40 WHERE id = ?", product);
        lots.lot(product, "A", LocalDate.of(2027, 5, 31), "3");

        clock.set(Instant.parse("2026-10-21T04:59:00Z"));
        job.tick();
        assertThat(dailyRuns()).isZero();

        clock.set(Instant.parse("2026-10-21T05:00:20Z"));
        job.tick();
        job.tick();
        clock.set(Instant.parse("2026-10-21T09:00:00Z"));
        job.tick();

        assertThat(dailyRuns()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT CAST(suggested_qty AS CHAR) FROM reorder_proposals WHERE product_id = ?",
                String.class, product)).isEqualTo("37.000");
    }

    @Test
    void followsAChangedSetting() {
        jdbc.update("UPDATE app_settings SET setting_value = '21:15' WHERE setting_key = 'reorder_job_time'");
        clock.set(Instant.parse("2026-10-21T05:00:00Z"));
        job.tick();
        assertThat(dailyRuns()).isZero();

        clock.set(Instant.parse("2026-10-21T20:15:00Z"));
        job.tick();

        assertThat(dailyRuns()).isEqualTo(1);
    }

    private int dailyRuns() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM reorder_runs WHERE triggered_by IS NULL AND started_at >= '2026-10-21 00:00:00'""",
                Integer.class);
    }
}
