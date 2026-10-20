package com.imsvet.ims.common.time;

import jakarta.validation.constraints.NotNull;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Business currency, formatting locale and time zone (ADR-033). Configured under {@code ims.business};
 * overridable from application settings once the settings module exists (Phase 1).
 */
@Validated
@ConfigurationProperties("ims.business")
public record BusinessProperties(@NotNull Currency currency, @NotNull Locale locale, @NotNull ZoneId timeZone) {
}
