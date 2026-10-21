package com.imsvet.ims.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Service test (S) of the audit write path: required fields, JSON values, actor and time (BR-AUDIT-002);
 * no secret ever reaches audit values (security.md §7).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AuditRecorderTest {

    private static final String ENTITY = "audit_recorder_test";

    @Autowired
    private AuditRecorder recorder;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestClock clock;

    @BeforeEach
    void resetClock() {
        clock.reset();
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** A system action: no HTTP request (the test framework binds a mock request by default). */
    @Test
    void recordsAllRequiredFieldsWithDecimalsAsStrings() {
        RequestContextHolder.resetRequestAttributes();

        recorder.record(AuditAction.SETTING_CHANGE, ENTITY, 7L, Map.of("rate", new BigDecimal("9.00")),
                Map.of("rate", new BigDecimal("19.00")));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT user_id, action, entity_id, old_values->>'$.rate' AS old_rate,
                       JSON_TYPE(new_values->'$.rate') AS new_type, ip_address, created_at
                FROM audit_log WHERE entity = ?""", ENTITY);
        assertThat(row.get("action")).isEqualTo("SETTING_CHANGE");
        assertThat(row.get("entity_id")).isEqualTo(7L);
        assertThat(row.get("old_rate")).isEqualTo("9.00");
        assertThat(row.get("new_type")).isEqualTo("STRING");
        assertThat(row.get("user_id")).isNull();
        assertThat(row.get("ip_address")).isNull();
        assertThat(((LocalDateTime) row.get("created_at")).toInstant(ZoneOffset.UTC)).isEqualTo(TestClock.START);
    }

    @Test
    void recordsTheClientIpOfTheCurrentRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("2001:db8::17");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        recorder.record(AuditAction.USER_UPDATE, ENTITY, 1L, null, Map.of("active", true));

        assertThat(jdbc.queryForObject("SELECT ip_address FROM audit_log WHERE entity = ?", String.class, ENTITY))
                .isEqualTo("2001:db8::17");
    }

    @Test
    void refusesPasswordsTokensAndHashesEvenNested() {
        assertThatThrownBy(() -> recorder.record(AuditAction.USER_CREATE, ENTITY, 1L, null,
                Map.of("passwordHash", "x"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recorder.record(AuditAction.PASSWORD_RESET, ENTITY, 1L, null,
                Map.of("user", Map.of("temporaryPassword", "x")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recorder.record(AuditAction.USER_UPDATE, ENTITY, 1L,
                Map.of("sessionToken", "x"), null)).isInstanceOf(IllegalArgumentException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity = ?", Integer.class, ENTITY))
                .isZero();
    }
}
