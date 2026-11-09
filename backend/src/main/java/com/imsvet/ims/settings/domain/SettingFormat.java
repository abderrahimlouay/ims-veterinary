package com.imsvet.ims.settings.domain;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
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

    THREE_ASCENDING_DAYS {
        @Override
        public String normalize(String raw) {
            String[] parts = raw.split(",", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException("Enter three numbers of days, smallest first, such as 30,60,90.");
            }
            List<Integer> days = new ArrayList<>();
            for (String part : parts) {
                days.add(parseInt(part));
            }
            if (days.get(0) < 1 || days.get(1) <= days.get(0) || days.get(2) <= days.get(1)) {
                throw new IllegalArgumentException("Enter three numbers of days, smallest first, such as 30,60,90.");
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
    },

    /** {@code true} or {@code false}, any case (ADR-082). */
    BOOLEAN {
        @Override
        public String normalize(String raw) {
            String value = raw.trim().toLowerCase(Locale.ROOT);
            if (!value.equals("true") && !value.equals("false")) {
                throw new IllegalArgumentException("Enter true or false.");
            }
            return value;
        }
    },

    /** {@code off}, or a number of days ≥ 0 (ADR-108). */
    OFF_OR_DAYS {
        @Override
        public String normalize(String raw) {
            if (raw.trim().equalsIgnoreCase("off")) {
                return "off";
            }
            int value;
            try {
                value = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Enter off or a number of days.");
            }
            if (value < 0) {
                throw new IllegalArgumentException("Enter off or a number of days.");
            }
            return String.valueOf(value);
        }
    },

    /** {@code off}, or a percentage above 0 and up to 100 with at most two decimals (ADR-172). */
    OFF_OR_PERCENT {
        @Override
        public String normalize(String raw) {
            if (raw.trim().equalsIgnoreCase("off")) {
                return "off";
            }
            BigDecimal value = decimal(raw, "Enter off or a percentage such as 1.00.");
            if (value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(100)) > 0 || value.stripTrailingZeros().scale() > 2) {
                throw new IllegalArgumentException("Enter off or a percentage above 0 and up to 100, two decimals at most.");
            }
            return value.setScale(2).toPlainString();
        }
    },

    /** An amount of money ≥ 0 with at most two decimals (ADR-172). */
    MONEY {
        @Override
        public String normalize(String raw) {
            return money(raw, false);
        }
    },

    /** An amount of money > 0 with at most two decimals (ADR-172). */
    POSITIVE_MONEY {
        @Override
        public String normalize(String raw) {
            return money(raw, true);
        }
    },

    /** {@code none}, or an amount of money ≥ 0 with at most two decimals (ADR-172). */
    NONE_OR_MONEY {
        @Override
        public String normalize(String raw) {
            return raw.trim().equalsIgnoreCase("none") ? "none" : money(raw, false);
        }
    },

    /** Up to 10 letters or digits, stored in upper case; empty for none (ADR-173). */
    SKU_PREFIX {
        @Override
        public String normalize(String raw) {
            String value = raw.trim().toUpperCase(Locale.ROOT);
            if (!value.matches("[A-Z0-9]{0,10}")) {
                throw new IllegalArgumentException("Enter up to 10 letters or digits, such as PRD, or nothing.");
            }
            return value;
        }
    },

    /** A time of day {@code HH:mm} in the business time zone, stored with two-digit hours (ADR-078, ADR-086). */
    TIME_OF_DAY {
        @Override
        public String normalize(String raw) {
            try {
                return LocalTime.parse(raw.trim(), FLEXIBLE_TIME).format(STORED_TIME);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Enter a time such as 06:00.");
            }
        }
    };

    /** Strict: no "24:00" turned into midnight. */
    private static final DateTimeFormatter FLEXIBLE_TIME = DateTimeFormatter.ofPattern("H:mm")
            .withResolverStyle(ResolverStyle.STRICT);

    private static final DateTimeFormatter STORED_TIME = DateTimeFormatter.ofPattern("HH:mm");

    public abstract String normalize(String raw);

    private static String money(String raw, boolean positive) {
        BigDecimal value = decimal(raw, "Enter an amount such as 100.00.");
        boolean outOfRange = positive ? value.signum() <= 0 : value.signum() < 0;
        if (outOfRange || value.stripTrailingZeros().scale() > 2 || value.precision() - value.scale() > 12) {
            throw new IllegalArgumentException(positive ? "Enter an amount above 0 with at most two decimals."
                    : "Enter an amount of at least 0 with at most two decimals.");
        }
        return value.setScale(2).toPlainString();
    }

    private static BigDecimal decimal(String raw, String message) {
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(message);
        }
    }

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Enter whole numbers only.");
        }
    }
}
