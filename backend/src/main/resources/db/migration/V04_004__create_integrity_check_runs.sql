-- Runs of the ledger integrity check (ADR-086, BR-STOCK-007, SD-122): when, how many lots, which lots differ
-- (lot, balance, ledger sum) and who asked (NULL = nightly job). Reports only; nothing is corrected.
CREATE TABLE integrity_check_runs (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    started_at     DATETIME(6) NOT NULL,
    finished_at    DATETIME(6) NULL,
    lots_checked   INT         NOT NULL DEFAULT 0,
    mismatch_count INT         NOT NULL DEFAULT 0,
    mismatches     JSON        NULL,
    triggered_by   BIGINT      NULL,
    CONSTRAINT pk_integrity_check_runs PRIMARY KEY (id),
    CONSTRAINT fk_integrity_check_runs_triggered_by FOREIGN KEY (triggered_by) REFERENCES users (id),
    CONSTRAINT ck_integrity_check_runs_counts CHECK (lots_checked >= 0 AND mismatch_count >= 0),
    INDEX idx_integrity_check_runs_started (started_at)
);
