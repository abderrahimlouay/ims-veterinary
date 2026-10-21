package com.imsvet.ims.settings.domain;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Text formats of setting values (ADR-044). {@link #normalize} returns the canonical stored text or throws
 * {@link IllegalArgumentException} with a message for the user.
 */
public enum SettingFormat {

    POSITIVE_INTEGER {
        @Override
        public String normalize(String raw) {
            int value = parseInt(raw);
            if (value < 1) {
                throw new IllegalArgumentException("Enter a whole number of at least 1.");
            }
            return String.valueOf(value);
        }
    },

    /** VAT in percent: 0 to 100 with at most two decimals (column scale DECIMAL(5,2)). */
    PERCENT {
        @Override
        public String normalize(String raw) {
            BigDecimal value;
            try {
                value = new BigDecimal(raw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Enter a percentage such as 9.00.");
            }
            boolean outOfRange = value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0;
            if (outOfRange || value.stripTrailingZeros().scale() > 2) {
                throw new IllegalArgumentException("Enter a percentage from 0 to 100 with at most two decimals.");
            }
            return value.setScale(2).toPlainString();
        }
    },

    /** BR-LOT-010: notice, warning and critical thresholds in days, strictly decreasing (ADR-044). */
    THREE_DESCENDING_DAYS {
        @Override
        public String normalize(String raw) {
            String[] parts = raw.split(",", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException("Enter three numbers of days, largest first, such as 60,30,15.");
            }
            List<Integer> days = new ArrayList<>();
            for (String part : parts) {
                days.add(parseInt(part));
            }
            if (days.get(2) < 1 || days.get(0) <= days.get(1) || days.get(1) <= days.get(2)) {
                throw new IllegalArgumentException("Enter three numbers of days, largest first, such as 60,30,15.");
            }
            return days.stream().map(String::valueOf).collect(Collectors.joining(","));
        }
    },

    CURRENCY_CODE {
        @Override
        public String normalize(String raw) {
            try {
                return Currency.getInstance(raw.trim()).getCurrencyCode();
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Enter an ISO 4217 currency code such as DZD.");
            }
        }
    },

    /** A language tag the JVM can format with, such as fr-DZ. */
    LOCALE_TAG {
        @Override
        public String normalize(String raw) {
            Locale locale = Locale.forLanguageTag(raw.trim());
            if (locale.getLanguage().isEmpty() || !Arrays.asList(Locale.getAvailableLocales()).contains(locale)) {
                throw new IllegalArgumentException("Enter a supported locale such as fr-DZ.");
            }
            return locale.toLanguageTag();
        }
    },

    ZONE_ID {
        @Override
        public String normalize(String raw) {
            try {
                return ZoneId.of(raw.trim()).getId();
            } catch (DateTimeException e) {
                throw new IllegalArgumentException("Enter a time zone such as Africa/Algiers.");
            }
        }
    };

    public abstract String normalize(String raw);

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Enter whole numbers only.");
        }
    }
}
