-- Supplier payments (database.md §3.7, ADR-132, SD-143): no invoice_id and no is_advance, the advance being the part
-- not allocated; a reversal is a new row naming the payment it reverses, once, with its reason (ADR-135, as SD-138).
-- Never changed or removed (BR-PAY-003).
CREATE TABLE supplier_payments (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    supplier_id         BIGINT        NOT NULL,
    method              VARCHAR(30)   NOT NULL,
    amount              DECIMAL(14,2) NOT NULL,
    reference           VARCHAR(80)   NULL,
    cash_session_id     BIGINT        NULL,
    reverses_payment_id BIGINT        NULL,
    reversal_reason     VARCHAR(500)  NULL,
    paid_at             DATETIME(6)   NOT NULL,
    created_by          BIGINT        NOT NULL,
    CONSTRAINT pk_supplier_payments PRIMARY KEY (id),
    CONSTRAINT uq_supplier_payments_reverses UNIQUE (reverses_payment_id),
    CONSTRAINT fk_supplier_payments_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id),
    CONSTRAINT fk_supplier_payments_session FOREIGN KEY (cash_session_id) REFERENCES cash_sessions (id),
    CONSTRAINT fk_supplier_payments_reverses FOREIGN KEY (reverses_payment_id) REFERENCES supplier_payments (id),
    CONSTRAINT fk_supplier_payments_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_supplier_payments_method CHECK (method IN ('cash', 'cheque', 'bank_transfer')),
    CONSTRAINT ck_supplier_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_supplier_payments_reference CHECK (method = 'cash' OR reference IS NOT NULL),
    CONSTRAINT ck_supplier_payments_cash_session CHECK (method <> 'cash' OR cash_session_id IS NOT NULL),
    CONSTRAINT ck_supplier_payments_reversal CHECK ((reverses_payment_id IS NULL) = (reversal_reason IS NULL)),
    INDEX idx_supplier_payments_supplier (supplier_id, paid_at)
);

CREATE TRIGGER trg_supplier_payments_no_update BEFORE UPDATE ON supplier_payments FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'supplier_payments is append-only (BR-PAY-003)';

CREATE TRIGGER trg_supplier_payments_no_delete BEFORE DELETE ON supplier_payments FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'supplier_payments is append-only (BR-PAY-003)';
