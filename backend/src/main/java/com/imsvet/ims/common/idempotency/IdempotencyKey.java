package com.imsvet.ims.common.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One claimed idempotency key (ADR-104, SD-128). Created by the claim, completed with the document's id in the same
 * transaction; a committed row therefore always names its document.
 */
@Entity
@Table(name = "idempotency_keys")
class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "resource_type", nullable = false, updatable = false, length = 40)
    private String resourceType;

    @Column(name = "resource_id")
    private Long resourceId;

    protected IdempotencyKey() {
    }

    String requestHash() {
        return requestHash;
    }

    String resourceType() {
        return resourceType;
    }

    Long resourceId() {
        return resourceId;
    }

    void complete(long resourceId) {
        this.resourceId = resourceId;
    }
}
