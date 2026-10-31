-- Overdue invoices block a new credit sale only when this is on (ADR-108): off, or the days overdue that are tolerated.
INSERT INTO app_settings (setting_key, setting_value, description, updated_at) VALUES
    ('credit_overdue_block_days', 'off', 'off, or a credit invoice overdue by more than this many days blocks a new credit sale (ADR-108)', UTC_TIMESTAMP(6));
