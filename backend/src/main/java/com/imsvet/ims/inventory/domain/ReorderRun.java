package com.imsvet.ims.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One generation of reorder proposals (ADR-078); {@code triggeredBy} {@code null} is the daily job. */
@Entity
@Table(name = "reorder_runs")
public class ReorderRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "triggered_by", updatable = false)
    private Long triggeredBy;

    @Column(name = "created_count", nullable = false, updatable = false)
    private int createdCount;

    @Column(name = "updated_count", nullable = false, updatable = false)
    private int updatedCount;

    @Column(name = "thresholds_incomplete", nullable = false, updatable = false)
    private int thresholdsIncomplete;

    protected ReorderRun() {
    }

    public ReorderRun(Instant startedAt, Long triggeredBy, int createdCount, int updatedCount,
            int thresholdsIncomplete) {
        this.startedAt = startedAt;
        this.triggeredBy = triggeredBy;
        this.createdCount = createdCount;
        this.updatedCount = updatedCount;
        this.thresholdsIncomplete = thresholdsIncomplete;
    }

    public Long id() {
        return id;
    }
}
