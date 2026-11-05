-- Limits of the receivables ageing columns, in days past the due date (ADR-148): not yet due, 1–30, 31–60, 61–90,
-- over 90 with the default.
INSERT INTO app_settings (setting_key, setting_value, description, updated_at) VALUES
    ('ageing_buckets', '30,60,90', 'Days past due that close the receivables ageing columns, smallest first (ADR-148)',
     UTC_TIMESTAMP(6));
