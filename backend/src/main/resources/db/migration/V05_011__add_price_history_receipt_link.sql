-- The receipt that appended a price-history entry, and whether that receipt was cancelled (ADR-106, SD-132): a
-- cancelled entry is kept, shown struck through, and ignored by the prefill and "latest price".
ALTER TABLE supplier_price_history
    ADD COLUMN receipt_id BIGINT  NULL AFTER created_by,
    ADD COLUMN cancelled  BOOLEAN NOT NULL DEFAULT FALSE AFTER receipt_id,
    ADD CONSTRAINT fk_supplier_price_history_receipt FOREIGN KEY (receipt_id) REFERENCES goods_receipts (id),
    ADD CONSTRAINT ck_supplier_price_history_cancelled CHECK (cancelled = FALSE OR receipt_id IS NOT NULL);
