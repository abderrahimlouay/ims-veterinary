package com.imsvet.ims.settings.application;

import com.imsvet.ims.common.time.BusinessSettings;
import com.imsvet.ims.settings.domain.SettingDefinition;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * {@link BusinessSettings} read from {@code app_settings} on each call (ADR-033, ADR-044), so a locale
 * change applies at once; currency and time zone change only through migrations.
 */
@Component
public class StoredBusinessSettings implements BusinessSettings {

    private final SettingsProvider settings;

    public StoredBusinessSettings(SettingsProvider settings) {
        this.settings = settings;
    }

    @Override
    public Currency currency() {
        return Currency.getInstance(settings.value(SettingDefinition.CURRENCY));
    }

    @Override
    public Locale locale() {
        return Locale.forLanguageTag(settings.value(SettingDefinition.LOCALE));
    }

    @Override
    public ZoneId timeZone() {
        return ZoneId.of(settings.value(SettingDefinition.BUSINESS_TIME_ZONE));
    }
}
