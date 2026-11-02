-- Customer refunds (database.md §3.8, ADR-128, SD-139, BR-RET-006/011): the money paid back for the credit note of a
-- return on an immediate sale, one row per tender, written by finance in the return's transaction. Not a negative
-- payment and not a reversal (a reversal cancels a whole payment, SD-138). Append-only (BR-PAY-003, SD-102), by one
-- trigger per event as the other money ledgers (ADR-017); the application account is also refused by its grants
-- (ADR-087). A cash refund names the refunder's session; a cheque or a transfer needs its reference.
CREATE TABLE customer_refunds (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    credit_note_id  BIGINT        NOT NULL,
    customer_id     BIGINT        NULL,
    method          VARCHAR(30)   NOT NULL,
    amount          DECIMAL(14,2) NOT NULL,
    reference       VARCHAR(80)   NULL,
    cash_session_id BIGINT        NULL,
    refunded_at     DATETIME(6)   NOT NULL,
    created_by      BIGINT        NOT NULL,
    CONSTRAINT pk_customer_refunds PRIMARY KEY (id),
    CONSTRAINT fk_customer_refunds_credit_note FOREIGN KEY (credit_note_id) REFERENCES sales_documents (id),
    CONSTRAINT fk_customer_refunds_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_customer_refunds_session FOREIGN KEY (cash_session_id) REFERENCES cash_sessions (id),
    CONSTRAINT fk_customer_refunds_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_customer_refunds_method CHECK (method IN ('cash', 'cheque', 'bank_transfer')),
    CONSTRAINT ck_customer_refunds_amount CHECK (amount > 0),
    CONSTRAINT ck_customer_refunds_reference CHECK (method = 'cash' OR reference IS NOT NULL),
    CONSTRAINT ck_customer_refunds_cash_session CHECK ((method = 'cash') = (cash_session_id IS NOT NULL)),
    INDEX idx_customer_refunds_credit_note (credit_note_id)
);

CREATE TRIGGER trg_customer_refunds_no_update BEFORE UPDATE ON customer_refunds FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'customer_refunds is append-only (BR-PAY-003)';

CREATE TRIGGER trg_customer_refunds_no_delete BEFORE DELETE ON customer_refunds FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'customer_refunds is append-only (BR-PAY-003)';
