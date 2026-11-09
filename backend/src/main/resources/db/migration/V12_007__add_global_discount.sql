-- Global discount (remise globale) of a sales document (ADR-175, BR-SALE-018): a percentage of each line, or an amount
-- off the total incl. tax, which the server spreads over the lines as a share of their amount excl. tax. The share
-- lowers line_ht, so line_vat and line_ttc follow and the VAT is that of what the customer pays. A credit-note line keeps
-- its credited amounts. global_discount_ht / global_discount_ttc on the document are what the spread took off, excl.
-- and incl. tax. Existing documents have none: the kind and value are NULL and every share is 0, so no amount changes.
-- line_ttc reads line_ht and line_vat, so it is dropped and added again around their new expressions (as in V08_002).
ALTER TABLE sales_documents
    ADD COLUMN global_discount_kind VARCHAR(10) NULL AFTER total_ttc,
    ADD COLUMN global_discount_value DECIMAL(14,2) NULL AFTER global_discount_kind,
    ADD COLUMN global_discount_ht DECIMAL(14,2) NOT NULL DEFAULT 0 AFTER global_discount_value,
    ADD COLUMN global_discount_ttc DECIMAL(14,2) NOT NULL DEFAULT 0 AFTER global_discount_ht,
    ADD CONSTRAINT ck_sales_documents_global_discount CHECK (
        (global_discount_kind IS NULL AND global_discount_value IS NULL)
        OR (global_discount_kind = 'percent' AND global_discount_value > 0 AND global_discount_value <= 100)
        OR (global_discount_kind = 'amount' AND global_discount_value > 0)),
    ADD CONSTRAINT ck_sales_documents_global_discount_totals
        CHECK (global_discount_ht >= 0 AND global_discount_ttc >= 0);

ALTER TABLE sales_document_lines
    DROP COLUMN line_ttc,
    ADD COLUMN global_discount_ht DECIMAL(14,2) NOT NULL DEFAULT 0 AFTER vat_rate;

ALTER TABLE sales_document_lines
    MODIFY COLUMN line_ht DECIMAL(14,2) GENERATED ALWAYS AS (COALESCE(credited_ht,
        ROUND(quantity * unit_price * (1 - discount_pct / 100), 2) - global_discount_ht)) STORED,
    MODIFY COLUMN line_vat DECIMAL(14,2) GENERATED ALWAYS AS (COALESCE(credited_vat,
        ROUND((ROUND(quantity * unit_price * (1 - discount_pct / 100), 2) - global_discount_ht) * vat_rate / 100, 2)))
        STORED;

ALTER TABLE sales_document_lines
    ADD COLUMN line_ttc DECIMAL(14,2) GENERATED ALWAYS AS (line_ht + line_vat) STORED AFTER line_vat,
    ADD CONSTRAINT ck_sales_document_lines_global_discount CHECK (global_discount_ht >= 0
        AND global_discount_ht <= ROUND(quantity * unit_price * (1 - discount_pct / 100), 2));
