-- How long after a sale a customer may return goods (ADR-131, BR-RET-009): off (no limit, the default), or a number of
-- days after the original's business date.
INSERT INTO app_settings (setting_key, setting_value, description, updated_at) VALUES
    ('customer_return_max_days', 'off', 'off, or the number of days after the sale within which a customer return is accepted (ADR-131)', UTC_TIMESTAMP(6));
