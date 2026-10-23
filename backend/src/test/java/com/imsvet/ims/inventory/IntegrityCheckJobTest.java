package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.inventory.application.IntegrityCheckJob;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-086: the nightly run happens once, at {@code integrity_check_time} in the business time zone (default 02:00,
 * Africa/Algiers = 01:00 UTC), read at each tick from the settings and the injected clock; the system is recorded
 * as having triggered it. Ticks are called directly: the scheduler is off in tests.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class IntegrityCheckJobTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestClock clock;

    @Autowired
    private IntegrityCheckJob job;

    @BeforeEach
    void before() {
        clock.reset();
    }

    @Test
    void runsOnceAtTheConfiguredBusinessTime() {
        clock.set(Instant.parse("2026-10-21T00:59:00Z"));
        job.tick();
        assertThat(nightlyRuns()).isZero();

        clock.set(Instant.parse("2026-10-21T01:00:20Z"));
        job.tick();
        job.tick();
        clock.set(Instant.parse("2026-10-21T01:00:50Z"));
        job.tick();

        assertThat(nightlyRuns()).isEqualTo(1);
    }

    @Test
    void followsAChangedSetting() {
        jdbc.update("UPDATE app_settings SET setting_value = '23:30' WHERE setting_key = 'integrity_check_time'");
        clock.set(Instant.parse("2026-10-21T01:00:00Z"));
        job.tick();
        assertThat(nightlyRuns()).isZero();

        clock.set(Instant.parse("2026-10-21T22:30:00Z"));
        job.tick();

        assertThat(nightlyRuns()).isEqualTo(1);
    }

    private int nightlyRuns() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM integrity_check_runs WHERE triggered_by IS NULL
                  AND started_at >= '2026-10-21 00:00:00'""", Integer.class);
    }
}
