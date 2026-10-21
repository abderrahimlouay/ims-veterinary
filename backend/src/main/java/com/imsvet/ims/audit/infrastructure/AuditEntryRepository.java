package com.imsvet.ims.audit.infrastructure;

import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.audit.domain.AuditEntry;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * Insert and search only: the audit log has no update or delete path (BR-AUDIT-002).
 */
public interface AuditEntryRepository extends Repository<AuditEntry, Long> {

    AuditEntry save(AuditEntry entry);

    /** Every filter is optional; {@code to} is exclusive. */
    @Query("""
            SELECT e FROM AuditEntry e
            WHERE (:entity IS NULL OR e.entity = :entity)
              AND (:entityId IS NULL OR e.entityId = :entityId)
              AND (:userId IS NULL OR e.userId = :userId)
              AND (:action IS NULL OR e.action = :action)
              AND (:from IS NULL OR e.createdAt >= :from)
              AND (:to IS NULL OR e.createdAt < :to)""")
    Page<AuditEntry> search(String entity, Long entityId, Long userId, AuditAction action, Instant from,
            Instant to, Pageable pageable);
}
