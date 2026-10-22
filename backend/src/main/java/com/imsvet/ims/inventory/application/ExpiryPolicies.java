package com.imsvet.ims.inventory.application;

import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.inventory.domain.ExpiryPolicy;
import com.imsvet.ims.settings.application.SettingsProvider;
import com.imsvet.ims.settings.domain.SettingDefinition;
import java.util.Arrays;
import org.springframework.stereotype.Component;

/**
 * The expiry policy of now: the business date from the injected clock in the business time zone (ADR-055) and the
 * {@code expiry_alert_days} thresholds read at each call, never hard-coded (BR-LOT-010, ADR-056).
 */
@Component
public class ExpiryPolicies {

    private final BusinessCalendar calendar;
    private final SettingsProvider settings;

    public ExpiryPolicies(BusinessCalendar calendar, SettingsProvider settings) {
        this.calendar = calendar;
        this.settings = settings;
    }

    public ExpiryPolicy current() {
        int[] days = Arrays.stream(settings.value(SettingDefinition.EXPIRY_ALERT_DAYS).split(","))
                .mapToInt(d -> Integer.parseInt(d.trim())).toArray();
        return new ExpiryPolicy(calendar.today(), days[0], days[1], days[2]);
    }
}
