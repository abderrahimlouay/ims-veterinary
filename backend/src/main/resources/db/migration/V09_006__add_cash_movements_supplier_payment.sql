-- The supplier payment a cash movement pays out, or the reversal of one paid back in (ADR-132, ADR-135, SD-146); a
-- movement has at most one source among a customer payment, a refund and a supplier payment.
ALTER TABLE cash_movements
    ADD COLUMN supplier_payment_id BIGINT NULL AFTER customer_refund_id,
    ADD CONSTRAINT fk_cash_movements_supplier_payment FOREIGN KEY (supplier_payment_id)
        REFERENCES supplier_payments (id),
    DROP CHECK ck_cash_movements_one_source;

ALTER TABLE cash_movements
    ADD CONSTRAINT ck_cash_movements_one_source CHECK ((customer_payment_id IS NOT NULL) + (customer_refund_id IS NOT NULL)
        + (supplier_payment_id IS NOT NULL) <= 1);
