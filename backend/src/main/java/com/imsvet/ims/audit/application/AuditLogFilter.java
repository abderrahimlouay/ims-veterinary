package com.imsvet.ims.audit.application;

import com.imsvet.ims.audit.domain.AuditAction;
import java.time.LocalDate;

/**
 * Optional filters of {@code GET /api/audit-logs} (api.md §4.14). {@code from}/{@code to} are business
 * dates, both inclusive, in the business time zone (ADR-010).
 */
public record AuditLogFilter(String entity, Long entityId, Long userId, AuditAction action, LocalDate from,
        LocalDate to) {
}
