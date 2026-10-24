-- What a receipt line changed on its lot outside the ledger, so a cancellation can restore it (ADR-106, SD-131):
-- whether the receipt created the lot; otherwise the lot's cost and status before the receipt; whether the
-- receipt blocked the lot (non-conforming line, ADR-095).
ALTER TABLE goods_receipt_lines
    ADD COLUMN lot_created       BOOLEAN       NOT NULL DEFAULT FALSE AFTER movement_id,
    ADD COLUMN lot_cost_before   DECIMAL(14,4) NULL AFTER lot_created,
    ADD COLUMN lot_status_before VARCHAR(30)   NULL AFTER lot_cost_before,
    ADD COLUMN lot_blocked       BOOLEAN       NOT NULL DEFAULT FALSE AFTER lot_status_before,
    ADD CONSTRAINT ck_goods_receipt_lines_lot_before CHECK (lot_created = (lot_cost_before IS NULL)
        AND lot_created = (lot_status_before IS NULL)),
    ADD CONSTRAINT ck_goods_receipt_lines_lot_status_before CHECK (lot_status_before IS NULL
        OR lot_status_before IN ('active', 'blocked', 'recalled'));
