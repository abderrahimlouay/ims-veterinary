-- One generation of reorder proposals (ADR-078): the daily job (triggered_by NULL) runs once per business day,
-- detected from these rows; "Generate now" records who asked. Counts of the result for traceability.
CREATE TABLE reorder_runs (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    started_at            DATETIME(6) NOT NULL,
    triggered_by          BIGINT      NULL,
    created_count         INT         NOT NULL,
    updated_count         INT         NOT NULL,
    thresholds_incomplete INT         NOT NULL,
    CONSTRAINT pk_reorder_runs PRIMARY KEY (id),
    CONSTRAINT fk_reorder_runs_triggered_by FOREIGN KEY (triggered_by) REFERENCES users (id),
    INDEX idx_reorder_runs_started (started_at)
);
