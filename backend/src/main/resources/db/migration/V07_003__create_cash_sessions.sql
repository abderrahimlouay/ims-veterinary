-- Cash sessions (database.md §3.8, FR-FIN-007, ADR-118): at most one open session per cashier, by a unique generated
-- column (BR-CASH-001, SD-009). Expected and counted amounts are set at closing; the variance is generated with the
-- service's formula (BR-CASH-002). A manager may close the session of another user, so closed_by is stored apart.
-- version for optimistic locking (ADR-027).
CREATE TABLE cash_sessions (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    cashier_id      BIGINT        NOT NULL,
    status          VARCHAR(30)   NOT NULL DEFAULT 'open',
    opened_at       DATETIME(6)   NOT NULL,
    closed_at       DATETIME(6)   NULL,
    closed_by       BIGINT        NULL,
    opening_amount  DECIMAL(14,2) NOT NULL DEFAULT 0,
    expected_amount DECIMAL(14,2) NULL,
    counted_amount  DECIMAL(14,2) NULL,
    variance_amount DECIMAL(14,2) GENERATED ALWAYS AS (counted_amount - expected_amount) STORED,
    open_cashier_id BIGINT        GENERATED ALWAYS AS (IF(status = 'open', cashier_id, NULL)) STORED,
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_cash_sessions PRIMARY KEY (id),
    CONSTRAINT uq_cash_sessions_open_cashier UNIQUE (open_cashier_id),
    CONSTRAINT fk_cash_sessions_cashier FOREIGN KEY (cashier_id) REFERENCES users (id),
    CONSTRAINT fk_cash_sessions_closed_by FOREIGN KEY (closed_by) REFERENCES users (id),
    CONSTRAINT ck_cash_sessions_status CHECK (status IN ('open', 'closed')),
    CONSTRAINT ck_cash_sessions_amounts CHECK (opening_amount >= 0 AND (counted_amount IS NULL OR counted_amount >= 0)),
    INDEX idx_cash_sessions_opened (opened_at)
);
