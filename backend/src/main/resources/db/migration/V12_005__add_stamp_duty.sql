-- Stamp duty (droit de timbre) on the cash part of an invoice or receipt paid at confirmation (ADR-172). Frozen on the
-- document at confirmation (BR-SALE-004); net_to_pay is what the customer pays. Only an immediate invoice or receipt
-- may carry one. The settings start off (stamp_duty_rate = off) until an administrator enters the accountant's figures;
-- the others are neutral, none of them a legal figure (CLAUDE.md §11).
ALTER TABLE sales_documents
    ADD COLUMN stamp_duty DECIMAL(14,2) NOT NULL DEFAULT 0 AFTER total_ttc,
    ADD COLUMN net_to_pay DECIMAL(14,2) GENERATED ALWAYS AS (total_ttc + stamp_duty) STORED AFTER stamp_duty,
    ADD CONSTRAINT ck_sales_documents_stamp_duty CHECK (stamp_duty >= 0),
    ADD CONSTRAINT ck_sales_documents_stamp_duty_scope
        CHECK (stamp_duty = 0 OR (doc_type IN ('invoice', 'receipt') AND is_credit_sale = FALSE));

INSERT INTO app_settings (setting_key, setting_value, description, updated_at) VALUES
    ('stamp_duty_rate', 'off', 'off, or the stamp duty in percent of the cash part of an immediate sale (ADR-172)',
     UTC_TIMESTAMP(6)),
    ('stamp_duty_step', '0.01', 'The cash part is rounded up to a whole number of this amount before the rate (ADR-172)',
     UTC_TIMESTAMP(6)),
    ('stamp_duty_minimum', '0.00', 'Smallest stamp duty charged (ADR-172)', UTC_TIMESTAMP(6)),
    ('stamp_duty_maximum', 'none', 'none, or the largest stamp duty charged (ADR-172)', UTC_TIMESTAMP(6)),
    ('stamp_duty_threshold', '0.00', 'No stamp duty when the cash part is at or below this amount (ADR-172)',
     UTC_TIMESTAMP(6)),
    ('stamp_duty_on_receipts', 'true', 'Whether receipts carry stamp duty, not only invoices (ADR-172)',
     UTC_TIMESTAMP(6));
