-- Cash movements (database.md §3.8, BR-CASH-005): append-only (BR-PAY-003, SD-102, ADR-118), by one trigger per event
-- as stock_movements (ADR-017); the application account is also refused by its grants (ADR-087). The amount is what
-- was kept, never what was tendered (ADR-118). customer_payment_id comes with the payments.
CREATE TABLE cash_movements (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    cash_session_id BIGINT        NOT NULL,
    direction       VARCHAR(30)   NOT NULL,
    method          VARCHAR(30)   NOT NULL,
    amount          DECIMAL(14,2) NOT NULL,
    label           VARCHAR(200)  NULL,
    created_by      BIGINT        NOT NULL,
    created_at      DATETIME(6)   NOT NULL,
    CONSTRAINT pk_cash_movements PRIMARY KEY (id),
    CONSTRAINT fk_cash_movements_session FOREIGN KEY (cash_session_id) REFERENCES cash_sessions (id),
    CONSTRAINT fk_cash_movements_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_cash_movements_direction CHECK (direction IN ('in', 'out')),
    CONSTRAINT ck_cash_movements_method CHECK (method IN ('cash', 'cheque', 'bank_transfer')),
    CONSTRAINT ck_cash_movements_amount CHECK (amount > 0)
);

CREATE TRIGGER trg_cash_movements_no_update BEFORE UPDATE ON cash_movements FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cash_movements is append-only (BR-PAY-003)';

CREATE TRIGGER trg_cash_movements_no_delete BEFORE DELETE ON cash_movements FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cash_movements is append-only (BR-PAY-003)';
