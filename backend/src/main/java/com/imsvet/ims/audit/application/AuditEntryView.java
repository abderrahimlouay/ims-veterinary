package com.imsvet.ims.audit.application;

import com.imsvet.ims.audit.domain.AuditAction;
import java.time.Instant;
import java.util.Map;

/**
 * Read model of one audit entry ({@code AuditEntry} in api.md §4.14); {@code username}/{@code userFullName}
 * name the acting user (ADR-046), all three are {@code null} for system actions.
 */
public record AuditEntryView(long id, Long userId, String username, String userFullName, AuditAction action, String entity, Long entityId,
        Map<String, Object> oldValues, Map<String, Object> newValues, String ipAddress, Instant createdAt) {
}
