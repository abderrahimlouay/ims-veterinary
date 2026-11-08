package com.imsvet.ims.sales.application;

import com.imsvet.ims.sales.domain.SalesDocType;
import com.imsvet.ims.sales.domain.StampDuty;
import com.imsvet.ims.settings.application.SettingsProvider;
import com.imsvet.ims.settings.domain.SettingDefinition;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** The stamp-duty scale from the settings (ADR-172); empty while {@code stamp_duty_rate} is {@code off}. */
@Component
class StampDutyScale {

    private final SettingsProvider settings;

    StampDutyScale(SettingsProvider settings) {
        this.settings = settings;
    }

    Optional<StampDuty> current() {
        String rate = settings.value(SettingDefinition.STAMP_DUTY_RATE);
        if (rate.equals("off")) {
            return Optional.empty();
        }
        String maximum = settings.value(SettingDefinition.STAMP_DUTY_MAXIMUM);
        return Optional.of(new StampDuty(new BigDecimal(rate),
                new BigDecimal(settings.value(SettingDefinition.STAMP_DUTY_STEP)),
                new BigDecimal(settings.value(SettingDefinition.STAMP_DUTY_MINIMUM)),
                maximum.equals("none") ? null : new BigDecimal(maximum),
                new BigDecimal(settings.value(SettingDefinition.STAMP_DUTY_THRESHOLD)),
                Boolean.parseBoolean(settings.value(SettingDefinition.STAMP_DUTY_ON_RECEIPTS))));
    }

    /** The stamp duty a document of this kind would carry on {@code cashPart}; zero when off or not applicable. */
    BigDecimal on(SalesDocType type, boolean creditSale, BigDecimal cashPart) {
        return current().filter(scale -> scale.appliesTo(type, creditSale)).map(scale -> scale.on(cashPart))
                .orElse(BigDecimal.ZERO.setScale(2));
    }
}
