package com.imsvet.ims.sales.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A customer return (W3, ADR-131): against a confirmed invoice or receipt, numbered {@code CR}, with its reason and the
 * one credit note it issued. Never changed or removed (BR-RET-007).
 */
@Entity
@Table(name = "customer_returns")
public class CustomerReturn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 30)
    private String number;

    @Column(name = "document_id", nullable = false, updatable = false)
    private Long documentId;

    @Column(name = "credit_note_id", nullable = false, updatable = false)
    private Long creditNoteId;

    @Column(nullable = false, updatable = false, length = 500)
    private String reason;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CustomerReturn() {
    }

    public CustomerReturn(String number, Long documentId, Long creditNoteId, String reason, Long createdBy,
            Instant now) {
        this.number = number;
        this.documentId = documentId;
        this.creditNoteId = creditNoteId;
        this.reason = reason;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    public Long id() {
        return id;
    }

    public String number() {
        return number;
    }

    public Long documentId() {
        return documentId;
    }

    public Long creditNoteId() {
        return creditNoteId;
    }

    public String reason() {
        return reason;
    }

    public Long createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
