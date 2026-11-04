package com.imsvet.ims.printing.application;

import com.imsvet.ims.settings.application.CompanyIdentity.Identity;
import com.imsvet.ims.settings.domain.CompanyProfile.Fields;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** French formats of printed documents (ADR-138): amounts "1 234,50", dates "04/11/2026 09:30", the identity block. */
public final class PrintFormats {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final char GROUP = ' ';

    private PrintFormats() {
    }

    /** Two decimals, a comma, a non-breaking space every three digits. */
    public static String money(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
        String plain = scaled.abs().toPlainString();
        String whole = plain.substring(0, plain.indexOf('.'));
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < whole.length(); i++) {
            if (i > 0 && (whole.length() - i) % 3 == 0) {
                grouped.append(GROUP);
            }
            grouped.append(whole.charAt(i));
        }
        return (scaled.signum() < 0 ? "-" : "") + grouped + "," + plain.substring(plain.indexOf('.') + 1);
    }

    /** "DA" for the dinar, as on the screens; another currency by its ISO code. */
    public static String symbol(Currency currency) {
        return "DZD".equals(currency.getCurrencyCode()) ? "DA" : currency.getCurrencyCode();
    }

    public static String money(BigDecimal amount, String currency) {
        return money(amount) + GROUP + currency;
    }

    /** A quantity without trailing zeros, decimal comma. */
    public static String quantity(BigDecimal quantity) {
        BigDecimal stripped = quantity.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString().replace('.', ',');
    }

    public static String percent(BigDecimal rate) {
        return quantity(rate) + GROUP + "%";
    }

    public static String date(LocalDate date) {
        return date == null ? null : DATE.format(date);
    }

    public static String dateTime(Instant instant, ZoneId zone) {
        return instant == null ? null : DATE_TIME.format(instant.atZone(zone));
    }

    /** The company block: name and the lines under it, the logo as a data URI, the footer; {@code null} if none. */
    public static Map<String, Object> company(Identity identity) {
        if (identity == null) {
            return null;
        }
        Fields f = identity.fields();
        List<String> lines = new ArrayList<>();
        add(lines, f.legalForm());
        add(lines, joined(", ", f.address(), f.wilaya()));
        add(lines, joined(" · ", f.phone() == null ? null : "Tél. " + f.phone(), f.email()));
        add(lines, joined(" · ", labelled("NIF", f.nif()), labelled("NIS", f.nis())));
        add(lines, joined(" · ", labelled("RC", f.rc()), labelled("AI", f.ai())));
        add(lines, labelled("Agrément vétérinaire n°", f.approvalNumber()));
        add(lines, labelled("RIB", f.bankAccount()));
        Map<String, Object> company = new LinkedHashMap<>();
        company.put("name", f.legalName());
        company.put("lines", lines);
        company.put("footer", f.footerText());
        company.put("logo", identity.logo() == null ? null
                : "data:" + identity.logoType() + ";base64," + Base64.getEncoder().encodeToString(identity.logo()));
        return company;
    }

    static String labelled(String label, String value) {
        return value == null ? null : label + " " + value;
    }

    static String joined(String separator, String... parts) {
        String text = Stream.of(parts).filter(Objects::nonNull).collect(Collectors.joining(separator));
        return text.isEmpty() ? null : text;
    }

    static void add(List<String> lines, String line) {
        if (line != null) {
            lines.add(line);
        }
    }
}
