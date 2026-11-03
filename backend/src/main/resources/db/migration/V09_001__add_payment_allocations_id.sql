-- SD-145 (ADR-134): a surrogate id, so that the unallocated part of a payment can be applied to the same invoice later;
-- created_at and created_by record when and by whom. The rows of Phase 7 and 8 keep their payment, invoice and amount,
-- dated and attributed as their payment. The update trigger is set aside only while those two columns are filled.
DROP TRIGGER trg_payment_allocations_no_update;

ALTER TABLE payment_allocations
    ADD INDEX idx_payment_allocations_payment (payment_id),
    ADD COLUMN created_at DATETIME(6) NULL,
    ADD COLUMN created_by BIGINT NULL;

UPDATE payment_allocations a
    JOIN customer_payments p ON p.id = a.payment_id
SET a.created_at = p.paid_at, a.created_by = p.created_by;

ALTER TABLE payment_allocations
    DROP PRIMARY KEY,
    ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST,
    MODIFY COLUMN created_at DATETIME(6) NOT NULL,
    MODIFY COLUMN created_by BIGINT NOT NULL,
    ADD CONSTRAINT fk_payment_allocations_created_by FOREIGN KEY (created_by) REFERENCES users (id);

CREATE TRIGGER trg_payment_allocations_no_update BEFORE UPDATE ON payment_allocations FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'payment_allocations is append-only (BR-PAY-003)';
