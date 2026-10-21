package com.imsvet.ims.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row of the append-only audit log (BR-AUDIT-002). Immutable: no setters, no update path; the
 * database triggers refuse UPDATE and DELETE as well (ADR-017).
 */
@Entity
@Immutable
@Table(name = "audit_log")
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 60)
    private AuditAction action;

    @Column(nullable = false, length = 60)
    private String entity;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "old_values", columnDefinition = "json")
    private String oldValues;

    @Column(name = "new_values", columnDefinition = "json")
    private String newValues;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditEntry() {
    }

    public AuditEntry(Long userId, AuditAction action, String entity, Long entityId, String oldValues,
            String newValues, String ipAddress, Instant createdAt) {
        this.userId = userId;
        this.action = action;
        this.entity = entity;
        this.entityId = entityId;
        this.oldValues = oldValues;
        this.newValues = newValues;
        this.ipAddress = ipAddress;
        this.createdAt = createdAt;
    }

    public Long id() {
        return id;
    }

    public Long userId() {
        return userId;
    }

    public AuditAction action() {
        return action;
    }

    public String entity() {
        return entity;
    }

    public Long entityId() {
        return entityId;
    }

    public String oldValues() {
        return oldValues;
    }

    public String newValues() {
        return newValues;
    }

    public String ipAddress() {
        return ipAddress;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
