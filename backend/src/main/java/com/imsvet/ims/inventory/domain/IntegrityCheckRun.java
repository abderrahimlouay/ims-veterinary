package com.imsvet.ims.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One run of the ledger integrity check (ADR-086, BR-STOCK-007): lots checked and the lots whose balance differs
 * from the sum of their movements (JSON list). {@code triggeredBy} {@code null} is the nightly job.
 */
@Entity
@Table(name = "integrity_check_runs")
public class IntegrityCheckRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "lots_checked", nullable = false)
    private int lotsChecked;

    @Column(name = "mismatch_count", nullable = false)
    private int mismatchCount;

    @Column(columnDefinition = "JSON")
    private String mismatches;

    @Column(name = "triggered_by", updatable = false)
    private Long triggeredBy;

    protected IntegrityCheckRun() {
    }

    public IntegrityCheckRun(Instant startedAt, Long triggeredBy) {
        this.startedAt = startedAt;
        this.triggeredBy = triggeredBy;
    }

    public void finish(Instant at, int checked, int mismatchesFound, String mismatchesJson) {
        this.finishedAt = at;
        this.lotsChecked = checked;
        this.mismatchCount = mismatchesFound;
        this.mismatches = mismatchesJson;
    }

    public Long id() {
        return id;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public int lotsChecked() {
        return lotsChecked;
    }

    public int mismatchCount() {
        return mismatchCount;
    }

    public String mismatches() {
        return mismatches;
    }

    public Long triggeredBy() {
        return triggeredBy;
    }
}
