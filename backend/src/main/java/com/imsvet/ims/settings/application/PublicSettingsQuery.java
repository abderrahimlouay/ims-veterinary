package com.imsvet.ims.settings.application;

import com.imsvet.ims.settings.domain.SettingDefinition;
import java.math.BigDecimal;
import java.util.Arrays;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /api/settings/public}: the settings any authenticated screen needs (thresholds are never
 * hard-coded in the UI, DR-015).
 */
@Service
public class PublicSettingsQuery {

    private final SettingsProvider settings;

    public PublicSettingsQuery(SettingsProvider settings) {
        this.settings = settings;
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public PublicSettingsView get() {
        return new PublicSettingsView(
                Arrays.stream(settings.value(SettingDefinition.EXPIRY_ALERT_DAYS).split(","))
                        .map(Integer::valueOf).toList(),
                new BigDecimal(settings.value(SettingDefinition.DEFAULT_VAT_RATE)),
                settings.value(SettingDefinition.CURRENCY),
                settings.value(SettingDefinition.LOCALE),
                settings.value(SettingDefinition.BUSINESS_TIME_ZONE),
                "true".equals(settings.value(SettingDefinition.OPENING_STOCK_OPEN)));
    }
}
