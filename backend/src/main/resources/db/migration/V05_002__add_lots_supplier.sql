-- The supplier of a lot arrives with suppliers (ADR-066, SD-117): set by the first receipt and kept (ADR-105);
-- empty for opening stock and lots found by a count.
ALTER TABLE lots
    ADD COLUMN supplier_id BIGINT NULL AFTER expiry_date,
    ADD CONSTRAINT fk_lots_supplier_id FOREIGN KEY (supplier_id) REFERENCES suppliers (id);
