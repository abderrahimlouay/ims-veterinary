package com.imsvet.ims.settings.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Every known setting (ADR-044): its format, whether administrators may change it through the API, and
 * whether every authenticated user may read it. Keys are created only by migrations, never at runtime.
 */
public enum SettingDefinition {

    EXPIRY_ALERT_DAYS("expiry_alert_days", SettingFormat.THREE_DESCENDING_DAYS, true, true),
    DEFAULT_VAT_RATE("default_vat_rate", SettingFormat.PERCENT, true, true),
    LOGIN_MAX_FAILURES("login_max_failures", SettingFormat.POSITIVE_INTEGER, true, false),
    LOGIN_WINDOW_MINUTES("login_window_minutes", SettingFormat.POSITIVE_INTEGER, true, false),
    LOGIN_LOCKOUT_MINUTES("login_lockout_minutes", SettingFormat.POSITIVE_INTEGER, true, false),
    CURRENCY("currency", SettingFormat.CURRENCY_CODE, false, true),
    LOCALE("locale", SettingFormat.LOCALE_TAG, true, true),
    BUSINESS_TIME_ZONE("business_time_zone", SettingFormat.ZONE_ID, false, true),
    OPENING_STOCK_OPEN("opening_stock_open", SettingFormat.BOOLEAN, true, true),
    REORDER_JOB_TIME("reorder_job_time", SettingFormat.TIME_OF_DAY, true, false),
    INTEGRITY_CHECK_TIME("integrity_check_time", SettingFormat.TIME_OF_DAY, true, false);

    private final String key;
    private final SettingFormat format;
    private final boolean editable;
    private final boolean publicValue;

    SettingDefinition(String key, SettingFormat format, boolean editable, boolean publicValue) {
        this.key = key;
        this.format = format;
        this.editable = editable;
        this.publicValue = publicValue;
    }

    public static Optional<SettingDefinition> byKey(String key) {
        return Arrays.stream(values()).filter(d -> d.key.equals(key)).findFirst();
    }

    public String key() {
        return key;
    }

    public SettingFormat format() {
        return format;
    }

    /** {@code false}: changes only through a reviewed migration (currency, time zone; ADR-044). */
    public boolean editable() {
        return editable;
    }

    public boolean publicValue() {
        return publicValue;
    }
}
