-- What a supplier credit note settles on an invoice of the same supplier (ADR-133, SD-144): never changed or removed
-- (BR-PAY-003); the same credit note may be applied to the same invoice again later.
CREATE TABLE supplier_credit_allocations (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    credit_note_id BIGINT        NOT NULL,
    invoice_id     BIGINT        NOT NULL,
    amount         DECIMAL(14,2) NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    created_by     BIGINT        NOT NULL,
    CONSTRAINT pk_supplier_credit_allocations PRIMARY KEY (id),
    CONSTRAINT fk_supplier_credit_allocations_credit_note FOREIGN KEY (credit_note_id) REFERENCES supplier_invoices (id),
    CONSTRAINT fk_supplier_credit_allocations_invoice FOREIGN KEY (invoice_id) REFERENCES supplier_invoices (id),
    CONSTRAINT fk_supplier_credit_allocations_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_supplier_credit_allocations_amount CHECK (amount > 0),
    CONSTRAINT ck_supplier_credit_allocations_distinct CHECK (credit_note_id <> invoice_id)
);

CREATE TRIGGER trg_supplier_credit_allocations_no_update BEFORE UPDATE ON supplier_credit_allocations FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'supplier_credit_allocations is append-only (BR-PAY-003)';

CREATE TRIGGER trg_supplier_credit_allocations_no_delete BEFORE DELETE ON supplier_credit_allocations FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'supplier_credit_allocations is append-only (BR-PAY-003)';
