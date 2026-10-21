package com.imsvet.ims.audit.application;

import com.imsvet.ims.audit.domain.AuditAction;
import java.time.Instant;
import java.util.Map;

/**
 * Read model of one audit entry ({@code AuditEntry} in api.md §4.14).
 */
public record AuditEntryView(long id, Long userId, AuditAction action, String entity, Long entityId,
        Map<String, Object> oldValues, Map<String, Object> newValues, String ipAddress, Instant createdAt) {
}
