-- Business currency, formatting locale and time zone move from configuration to settings (ADR-033, ADR-044).
-- Only the locale is editable through the API; currency and time zone change only through a migration.
INSERT INTO app_settings (setting_key, setting_value, description, updated_at) VALUES
    ('currency', 'DZD', 'ISO 4217 currency of all amounts (read-only: changes only through a migration)', UTC_TIMESTAMP(6)),
    ('locale', 'fr-DZ', 'Locale for numbers, dates and amounts on screens and documents', UTC_TIMESTAMP(6)),
    ('business_time_zone', 'Africa/Algiers', 'Time zone of business dates (read-only: changes only through a migration)', UTC_TIMESTAMP(6));
