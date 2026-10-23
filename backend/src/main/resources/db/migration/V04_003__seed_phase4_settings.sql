-- Phase 4 settings (ADR-044 formats boolean and HH:mm), all editable by settings.manage (Administrator).
INSERT INTO app_settings (setting_key, setting_value, description, updated_at) VALUES
    ('opening_stock_open', 'true', 'Opening stock may be entered; switch off at go-live, stock found later goes through an inventory count (ADR-082)', UTC_TIMESTAMP(6)),
    ('reorder_job_time', '06:00', 'Business time of the daily reorder-proposal generation (ADR-078)', UTC_TIMESTAMP(6)),
    ('integrity_check_time', '02:00', 'Business time of the nightly ledger integrity check (ADR-086)', UTC_TIMESTAMP(6));
