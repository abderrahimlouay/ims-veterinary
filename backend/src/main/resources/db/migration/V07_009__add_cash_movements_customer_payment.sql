-- The payment a cash movement belongs to (database.md §3.8); none on a later manual movement (Phase 9).
ALTER TABLE cash_movements
    ADD COLUMN customer_payment_id BIGINT NULL AFTER label,
    ADD CONSTRAINT fk_cash_movements_customer_payment FOREIGN KEY (customer_payment_id)
        REFERENCES customer_payments (id);
