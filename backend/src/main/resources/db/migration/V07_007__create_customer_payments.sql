-- Customer payments (database.md §3.8, ADR-118): every immediate sale creates them; no customer on an anonymous sale.
-- Append-only (BR-PAY-003, SD-102): a reversal is a new row pointing to the payment it reverses, at most once (SD-138);
-- its amount stays positive and the balance reads reverses_payment_id. A cash payment names its session.
CREATE TABLE customer_payments (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    customer_id         BIGINT        NULL,
    method              VARCHAR(30)   NOT NULL,
    amount              DECIMAL(14,2) NOT NULL,
    reference           VARCHAR(80)   NULL,
    cash_session_id     BIGINT        NULL,
    reverses_payment_id BIGINT        NULL,
    reversal_reason     VARCHAR(500)  NULL,
    paid_at             DATETIME(6)   NOT NULL,
    created_by          BIGINT        NOT NULL,
    CONSTRAINT pk_customer_payments PRIMARY KEY (id),
    CONSTRAINT uq_customer_payments_reverses UNIQUE (reverses_payment_id),
    CONSTRAINT fk_customer_payments_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_customer_payments_session FOREIGN KEY (cash_session_id) REFERENCES cash_sessions (id),
    CONSTRAINT fk_customer_payments_reverses FOREIGN KEY (reverses_payment_id) REFERENCES customer_payments (id),
    CONSTRAINT fk_customer_payments_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_customer_payments_method CHECK (method IN ('cash', 'cheque', 'bank_transfer')),
    CONSTRAINT ck_customer_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_customer_payments_reference CHECK (method = 'cash' OR reference IS NOT NULL),
    CONSTRAINT ck_customer_payments_cash_session CHECK (method <> 'cash' OR cash_session_id IS NOT NULL),
    CONSTRAINT ck_customer_payments_reversal CHECK ((reverses_payment_id IS NULL) = (reversal_reason IS NULL)),
    INDEX idx_customer_payments_customer (customer_id, paid_at)
);

CREATE TRIGGER trg_customer_payments_no_update BEFORE UPDATE ON customer_payments FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'customer_payments is append-only (BR-PAY-003)';

CREATE TRIGGER trg_customer_payments_no_delete BEFORE DELETE ON customer_payments FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'customer_payments is append-only (BR-PAY-003)';
