-- Source Schema settings (VAT computation itself is OD-007) and login throttling limits (ADR-038).
INSERT INTO app_settings (setting_key, setting_value, description, updated_at) VALUES
    ('expiry_alert_days', '60,30,15', 'Days before expiry at which lot alert levels start', UTC_TIMESTAMP(6)),
    ('default_vat_rate', '9.00', 'Default VAT rate in percent for new products', UTC_TIMESTAMP(6)),
    ('login_max_failures', '5', 'Failed logins per username and IP before throttling', UTC_TIMESTAMP(6)),
    ('login_window_minutes', '15', 'Window in minutes in which failed logins are counted', UTC_TIMESTAMP(6)),
    ('login_lockout_minutes', '15', 'Minutes a throttled username and IP pair is refused', UTC_TIMESTAMP(6));
