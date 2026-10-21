package com.imsvet.ims.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.audit.application.AuditEntryView;
import com.imsvet.ims.audit.application.AuditLogFilter;
import com.imsvet.ims.audit.application.AuditLogQuery;
import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.paging.PageResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service test (S) of the audit read path (api.md §4.14): filters, newest first, paging envelope, business
 * dates in Africa/Algiers (UTC+1, ADR-033), bounds.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AuditLogQueryTest {

    private static final String ENTITY = "audit_query_test";

    @Autowired
    private AuditRecorder recorder;

    @Autowired
    private AuditLogQuery query;

    @Autowired
    private TestClock clock;

    @BeforeEach
    void recordEntries() {
        record("2026-10-19T22:59:59Z", AuditAction.SETTING_CHANGE, 1L);
        record("2026-10-19T23:00:00Z", AuditAction.USER_CREATE, 2L);
        record("2026-10-20T12:00:00Z", AuditAction.USER_UPDATE, 2L);
        record("2026-10-20T22:59:59Z", AuditAction.USER_DEACTIVATE, 2L);
        record("2026-10-20T23:00:00Z", AuditAction.SETTING_CHANGE, 1L);
    }

    @Test
    void returnsNewestFirstWithThePagingEnvelope() {
        PageResponse<AuditEntryView> page = query.search(filter(null, null, null, null), 0, 2);

        assertThat(page.content()).extracting(AuditEntryView::createdAt).containsExactly(
                Instant.parse("2026-10-20T23:00:00Z"), Instant.parse("2026-10-20T22:59:59Z"));
        assertThat(page.page().number()).isZero();
        assertThat(page.page().size()).isEqualTo(2);
        assertThat(page.page().totalElements()).isEqualTo(5);
        assertThat(page.page().totalPages()).isEqualTo(3);
    }

    @Test
    void filtersByEntityIdAndAction() {
        assertThat(query.search(filter(2L, null, null, null), 0, 20).content()).hasSize(3);
        assertThat(query.search(filter(null, AuditAction.SETTING_CHANGE, null, null), 0, 20).content())
                .extracting(AuditEntryView::entityId).containsOnly(1L).hasSize(2);
    }

    @Test
    void businessDateRangeIsInclusiveInTheBusinessTimeZone() {
        LocalDate oct20 = LocalDate.of(2026, 10, 20);

        assertThat(query.search(filter(null, null, oct20, oct20), 0, 20).content())
                .extracting(AuditEntryView::action)
                .containsExactly(AuditAction.USER_DEACTIVATE, AuditAction.USER_UPDATE, AuditAction.USER_CREATE);
    }

    @Test
    void returnsTheRecordedValues() {
        AuditEntryView latest = query.search(filter(null, null, null, null), 0, 1).content().getFirst();

        assertThat(latest.entity()).isEqualTo(ENTITY);
        assertThat(latest.newValues()).containsEntry("step", "2026-10-20T23:00:00Z");
        assertThat(latest.oldValues()).isNull();
    }

    @Test
    void rejectsInvalidBoundsAsValidationFailures() {
        assertThatThrownBy(() -> query.search(filter(null, null, null, null), 0, 101))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> query.search(filter(null, null, null, null), -1, 20))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.search(
                filter(null, null, LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 20)), 0, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    private void record(String at, AuditAction action, long entityId) {
        clock.set(Instant.parse(at));
        recorder.record(action, ENTITY, entityId, null, Map.of("step", at));
    }

    private static AuditLogFilter filter(Long entityId, AuditAction action, LocalDate from, LocalDate to) {
        return new AuditLogFilter(ENTITY, entityId, null, action, from, to);
    }
}
