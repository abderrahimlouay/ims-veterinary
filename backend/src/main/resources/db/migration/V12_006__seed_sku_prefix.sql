-- A product created without a SKU gets PREFIX-NNNNNN from a counter that never resets (ADR-173); editable, audited
-- like every setting. Empty: no prefix, the number alone.
INSERT INTO app_settings (setting_key, setting_value, description, updated_at) VALUES
    ('sku_prefix', 'PRD', 'Prefix of the SKUs generated for products created without one; empty for none (ADR-173)',
     UTC_TIMESTAMP(6));
