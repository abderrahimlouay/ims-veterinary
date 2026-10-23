package com.imsvet.ims.settings.application;

import java.math.BigDecimal;
import java.util.List;

/**
 * Settings every authenticated screen needs (api.md §4.14): expiry thresholds (BR-LOT-010), default VAT,
 * currency, locale and business time zone (ADR-033), and whether opening stock may still be entered (ADR-082). No
 * security settings.
 */
public record PublicSettingsView(List<Integer> expiryAlertDays, BigDecimal defaultVatRate, String currency,
        String locale, String timeZone, boolean openingStockOpen) {
}
