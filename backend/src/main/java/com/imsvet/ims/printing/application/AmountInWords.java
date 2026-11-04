package com.imsvet.ims.printing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * An amount in Algerian dinars written out in French for invoices and credit notes (ADR-141): "mille deux cent
 * trente-quatre dinars algériens et cinquante centimes". Traditional spelling: hyphens below one hundred only, "et"
 * in 21, 31, 41, 51, 61 and 71, "vingts" and "cents" plural when they end the number or come before millions or
 * billions, "mille" invariable, "de" after a round number of millions or billions.
 */
public final class AmountInWords {

    private static final String[] UNITS = {"zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit",
        "neuf", "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize", "dix-sept", "dix-huit", "dix-neuf"};
    private static final String[] TENS = {"", "", "vingt", "trente", "quarante", "cinquante", "soixante", "soixante",
        "quatre-vingt", "quatre-vingt"};
    private static final BigDecimal MAX = new BigDecimal("999999999999.99");

    private AmountInWords() {
    }

    /** A non-negative amount of at most 999 999 999 999,99, rounded to the centime; capitalised. */
    public static String dinars(BigDecimal amount) {
        if (amount.signum() < 0 || amount.compareTo(MAX) > 0) {
            throw new IllegalArgumentException("Amount out of range: " + amount);
        }
        BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP);
        long whole = rounded.longValue();
        int centimes = rounded.subtract(BigDecimal.valueOf(whole)).movePointRight(2).intValueExact();
        StringBuilder text = new StringBuilder(number(whole));
        if (whole >= 1_000_000 && whole % 1_000_000 == 0) {
            text.append(" de");
        }
        text.append(whole > 1 ? " dinars algériens" : " dinar algérien");
        if (centimes > 0) {
            text.append(" et ").append(number(centimes)).append(centimes > 1 ? " centimes" : " centime");
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** A whole number of at most 999 999 999 999 in words. */
    static String number(long n) {
        if (n == 0) {
            return UNITS[0];
        }
        StringBuilder out = new StringBuilder();
        long billions = n / 1_000_000_000L;
        long millions = n / 1_000_000L % 1000;
        long thousands = n / 1000 % 1000;
        int rest = (int) (n % 1000);
        if (billions > 0) {
            append(out, belowThousand((int) billions, true) + (billions > 1 ? " milliards" : " milliard"));
        }
        if (millions > 0) {
            append(out, belowThousand((int) millions, true) + (millions > 1 ? " millions" : " million"));
        }
        if (thousands > 0) {
            append(out, thousands == 1 ? "mille" : belowThousand((int) thousands, false) + " mille");
        }
        if (rest > 0) {
            append(out, belowThousand(rest, true));
        }
        return out.toString();
    }

    private static void append(StringBuilder out, String part) {
        if (!out.isEmpty()) {
            out.append(' ');
        }
        out.append(part);
    }

    /** 1–999; {@code last}: nothing but a noun (million, milliard) or the end follows, so "cents"/"vingts" agree. */
    private static String belowThousand(int n, boolean last) {
        int hundreds = n / 100;
        int rest = n % 100;
        StringBuilder out = new StringBuilder();
        if (hundreds > 0) {
            out.append(hundreds == 1 ? "cent" : UNITS[hundreds] + " cent");
            if (hundreds > 1 && rest == 0 && last) {
                out.append('s');
            }
        }
        if (rest > 0) {
            append(out, belowHundred(rest, last));
        }
        return out.toString();
    }

    private static String belowHundred(int n, boolean last) {
        if (n < 20) {
            return UNITS[n];
        }
        int ten = n / 10;
        int unit = n % 10;
        if (ten == 7 || ten == 9) {
            int teen = 10 + unit;
            return TENS[ten] + (ten == 7 && unit == 1 ? " et " : "-") + UNITS[teen];
        }
        if (unit == 0) {
            return TENS[ten] + (ten == 8 && last ? "s" : "");
        }
        return TENS[ten] + (unit == 1 && ten != 8 ? " et " : "-") + UNITS[unit];
    }
}
