-- Application settings (database.md §3.1, FR-SEC-003); key/value columns renamed (SD-006).
CREATE TABLE app_settings (
    setting_key   VARCHAR(80) NOT NULL,
    setting_value TEXT        NOT NULL,
    description   TEXT        NULL,
    updated_at    DATETIME(6) NOT NULL,
    CONSTRAINT pk_app_settings PRIMARY KEY (setting_key)
);
