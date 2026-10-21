package com.imsvet.ims.common.time;

import java.time.ZoneId;
import java.util.Currency;
import java.util.Locale;

/**
 * Business currency, formatting locale and time zone (ADR-033), stored as application settings and
 * provided by the {@code settings} module (ADR-044). In {@code common} so every module can use it.
 */
public interface BusinessSettings {

    Currency currency();

    Locale locale();

    ZoneId timeZone();
}
