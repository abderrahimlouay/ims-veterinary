-- What a payment settles (database.md §3.8, BR-PAY-002): one row per payment and document. Append-only (SD-102); a
-- reversal never touches it, so the settled amount of a document leaves out the reversed payments.
CREATE TABLE payment_allocations (
    payment_id BIGINT        NOT NULL,
    invoice_id BIGINT        NOT NULL,
    amount     DECIMAL(14,2) NOT NULL,
    CONSTRAINT pk_payment_allocations PRIMARY KEY (payment_id, invoice_id),
    CONSTRAINT fk_payment_allocations_payment FOREIGN KEY (payment_id) REFERENCES customer_payments (id),
    CONSTRAINT fk_payment_allocations_invoice FOREIGN KEY (invoice_id) REFERENCES sales_documents (id),
    CONSTRAINT ck_payment_allocations_amount CHECK (amount > 0),
    INDEX idx_payment_allocations_invoice (invoice_id)
);

CREATE TRIGGER trg_payment_allocations_no_update BEFORE UPDATE ON payment_allocations FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'payment_allocations is append-only (BR-PAY-003)';

CREATE TRIGGER trg_payment_allocations_no_delete BEFORE DELETE ON payment_allocations FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'payment_allocations is append-only (BR-PAY-003)';
