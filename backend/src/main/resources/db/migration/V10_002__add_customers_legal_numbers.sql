-- A customer's legal numbers next to the NIF (tax_id), optional, printed on invoices when present (ADR-141, SD-148).
ALTER TABLE customers
    ADD COLUMN nis VARCHAR(40) NULL AFTER tax_id,
    ADD COLUMN rc  VARCHAR(40) NULL AFTER nis,
    ADD COLUMN ai  VARCHAR(40) NULL AFTER rc;
