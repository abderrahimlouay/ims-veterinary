-- The refund a cash movement pays out (database.md §3.8, ADR-128, SD-139); a movement belongs to a payment or to a
-- refund, never both.
ALTER TABLE cash_movements
    ADD COLUMN customer_refund_id BIGINT NULL AFTER customer_payment_id,
    ADD CONSTRAINT fk_cash_movements_customer_refund FOREIGN KEY (customer_refund_id)
        REFERENCES customer_refunds (id),
    ADD CONSTRAINT ck_cash_movements_one_source CHECK (customer_payment_id IS NULL OR customer_refund_id IS NULL);
