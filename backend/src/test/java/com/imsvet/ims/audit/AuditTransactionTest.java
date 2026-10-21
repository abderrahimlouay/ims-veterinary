package com.imsvet.ims.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BR-AUDIT-003: the audit entry commits with the action or not at all. Not {@code @Transactional}: each
 * test drives its own transaction, and nothing it writes is committed.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditTransactionTest {

    @Autowired
    private AuditRecorder recorder;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transaction;

    @Test
    void recordingOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> recorder.record(AuditAction.SETTING_CHANGE, "app_settings", null, null, null))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void whenTheAuditEntryCannotBeWrittenTheActionIsRolledBack() {
        String before = currentVatRate();
        String tooLongEntity = "x".repeat(61);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE app_settings SET setting_value = '19.00' WHERE setting_key = 'default_vat_rate'");
            recorder.record(AuditAction.SETTING_CHANGE, tooLongEntity, null, Map.of("value", before),
                    Map.of("value", "19.00"));
        })).isInstanceOf(DataAccessException.class);

        assertThat(currentVatRate()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity = ?", Integer.class,
                tooLongEntity)).isZero();
    }

    private String currentVatRate() {
        return jdbc.queryForObject("SELECT setting_value FROM app_settings WHERE setting_key = 'default_vat_rate'",
                String.class);
    }
}
