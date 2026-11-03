-- What a supplier payment settles on the supplier's invoices (ADR-132, SD-143): one payment, several invoices; the same
-- payment may be applied to the same invoice again later. Never changed or removed (BR-PAY-003); a reversed payment's
-- allocations stop counting.
CREATE TABLE supplier_payment_allocations (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    payment_id BIGINT        NOT NULL,
    invoice_id BIGINT        NOT NULL,
    amount     DECIMAL(14,2) NOT NULL,
    created_at DATETIME(6)   NOT NULL,
    created_by BIGINT        NOT NULL,
    CONSTRAINT pk_supplier_payment_allocations PRIMARY KEY (id),
    CONSTRAINT fk_supplier_payment_allocations_payment FOREIGN KEY (payment_id) REFERENCES supplier_payments (id),
    CONSTRAINT fk_supplier_payment_allocations_invoice FOREIGN KEY (invoice_id) REFERENCES supplier_invoices (id),
    CONSTRAINT fk_supplier_payment_allocations_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_supplier_payment_allocations_amount CHECK (amount > 0)
);

CREATE TRIGGER trg_supplier_payment_allocations_no_update BEFORE UPDATE ON supplier_payment_allocations FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'supplier_payment_allocations is append-only (BR-PAY-003)';

CREATE TRIGGER trg_supplier_payment_allocations_no_delete BEFORE DELETE ON supplier_payment_allocations FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'supplier_payment_allocations is append-only (BR-PAY-003)';
