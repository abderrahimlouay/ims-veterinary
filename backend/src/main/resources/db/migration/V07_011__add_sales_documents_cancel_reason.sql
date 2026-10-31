-- The mandatory reason of a cancelled confirmed document (ADR-119, SD-135); an abandoned draft has none.
ALTER TABLE sales_documents ADD COLUMN cancel_reason VARCHAR(500) NULL AFTER cancelled_at;
