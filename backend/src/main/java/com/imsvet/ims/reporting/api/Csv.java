package com.imsvet.ims.reporting.api;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A CSV file for French-locale spreadsheets (ADR-149): semicolon separator, decimal comma without grouping, dates
 * {@code dd/MM/yyyy} and times {@code dd/MM/yyyy HH:mm} in the business time zone, UTF-8 with a byte-order mark, CRLF
 * line ends. A text cell starting with {@code = + - @}, a tab or a carriage return gets a leading apostrophe so that a
 * spreadsheet never runs it as a formula; numbers are written as numbers.
 */
final class Csv {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final ZoneId zone;
    private final List<String> lines = new ArrayList<>();

    Csv(ZoneId zone) {
        this.zone = zone;
    }

    Csv row(Object... cells) {
        lines.add(String.join(";", Arrays.stream(cells).map(this::cell).toList()));
        return this;
    }

    Csv rows(List<Object[]> rows) {
        rows.forEach(this::row);
        return this;
    }

    byte[] bytes() {
        byte[] text = (String.join("\r\n", lines) + "\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[BOM.length + text.length];
        System.arraycopy(BOM, 0, out, 0, BOM.length);
        System.arraycopy(text, 0, out, BOM.length, text.length);
        return out;
    }

    private String cell(Object value) {
        return switch (value) {
            case null -> "";
            case BigDecimal number -> number.toPlainString().replace('.', ',');
            case Number number -> number.toString();
            case LocalDate date -> DATE.format(date);
            case Instant instant -> DATE_TIME.format(instant.atZone(zone));
            case Boolean flag -> flag ? "Oui" : "Non";
            default -> text(value.toString());
        };
    }

    private static String text(String raw) {
        String safe = !raw.isEmpty() && "=+-@\t\r".indexOf(raw.charAt(0)) >= 0 ? "'" + raw : raw;
        if (safe.contains(";") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }
}
