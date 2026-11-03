-- Manual cash movements (ADR-136, SD-146): cash in or out of a session with a mandatory label, and nothing else as
-- source. From here every movement has exactly one source: a customer payment, a refund, a supplier payment or manual.
ALTER TABLE cash_movements
    ADD COLUMN manual BOOLEAN NOT NULL DEFAULT FALSE AFTER supplier_payment_id,
    DROP CHECK ck_cash_movements_one_source;

ALTER TABLE cash_movements
    ADD CONSTRAINT ck_cash_movements_one_source CHECK ((customer_payment_id IS NOT NULL) + (customer_refund_id IS NOT NULL)
        + (supplier_payment_id IS NOT NULL) + manual = 1),
    ADD CONSTRAINT ck_cash_movements_manual CHECK (NOT manual OR (method = 'cash' AND label IS NOT NULL
        AND TRIM(label) <> ''));
