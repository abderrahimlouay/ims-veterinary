-- What a customer return needs on the sales lines (ADR-130, SD-140, BR-RET-001, BR-RET-004):
-- returned_quantity_base, raised only by a return under the original document's lock and never above the sold
-- quantity (the database backstop of BR-RET-001); credited_ht / credited_vat, set only on credit-note lines (the
-- pro-rata, or the exact remainder on the return that completes a line), which line_ht / line_vat then take instead of
-- the formula. Existing lines keep their amounts: both credited columns are NULL. line_ttc depends on the two amounts,
-- so it is dropped and added again around their new expressions (MySQL cannot change a column another generated
-- column reads).
ALTER TABLE sales_document_lines
    DROP COLUMN line_ttc,
    ADD COLUMN returned_quantity_base DECIMAL(14,3) NOT NULL DEFAULT 0 AFTER quantity_base,
    ADD COLUMN credited_ht DECIMAL(14,2) NULL AFTER vat_rate,
    ADD COLUMN credited_vat DECIMAL(14,2) NULL AFTER credited_ht;

ALTER TABLE sales_document_lines
    MODIFY COLUMN line_ht DECIMAL(14,2) GENERATED ALWAYS AS (COALESCE(credited_ht,
        ROUND(quantity * unit_price * (1 - discount_pct / 100), 2))) STORED,
    MODIFY COLUMN line_vat DECIMAL(14,2) GENERATED ALWAYS AS (COALESCE(credited_vat,
        ROUND(ROUND(quantity * unit_price * (1 - discount_pct / 100), 2) * vat_rate / 100, 2))) STORED;

ALTER TABLE sales_document_lines
    ADD COLUMN line_ttc DECIMAL(14,2) GENERATED ALWAYS AS (line_ht + line_vat) STORED AFTER line_vat,
    ADD CONSTRAINT ck_sales_document_lines_returned
        CHECK (returned_quantity_base >= 0 AND returned_quantity_base <= quantity_base),
    ADD CONSTRAINT ck_sales_document_lines_credited CHECK ((credited_ht IS NULL) = (credited_vat IS NULL)
        AND (credited_ht IS NULL OR (credited_ht >= 0 AND credited_vat >= 0)));
