package com.imsvet.ims.reporting.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A report laid out for export (ADR-149): French title and filter lines, then sections of columns, rows and total rows.
 * Cells are typed values ({@code BigDecimal}, {@code Long}, {@code LocalDate}, {@code Instant}, {@code String} or
 * {@code null}); each renderer (CSV, PDF) formats them. {@code wide}: printed in A4 landscape.
 */
public record ReportTable(String title, String fileName, boolean wide, List<String> filters, List<Section> sections) {

    public record Column(String label, boolean numeric) {
    }

    /** {@code heading} may be {@code null} for a report with a single section. */
    public record Section(String heading, List<Column> columns, List<List<Object>> rows, List<List<Object>> totals) {
    }

    /** {@code name} plus the period, such as {@code chiffre-affaires-2026-11-01-2026-11-05}. */
    static String fileName(String name, LocalDate from, LocalDate to) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_") + (from == null ? "" : "-" + from) + (to == null ? "" : "-" + to);
    }

    /** Builds one section: text columns, then numeric ones marked with {@link #number}. */
    static final class SectionBuilder {

        private final String heading;
        private final List<Column> columns = new ArrayList<>();
        private final List<List<Object>> rows = new ArrayList<>();
        private final List<List<Object>> totals = new ArrayList<>();

        SectionBuilder(String heading) {
            this.heading = heading;
        }

        SectionBuilder text(String... labels) {
            Arrays.stream(labels).forEach(l -> columns.add(new Column(l, false)));
            return this;
        }

        SectionBuilder number(String... labels) {
            Arrays.stream(labels).forEach(l -> columns.add(new Column(l, true)));
            return this;
        }

        SectionBuilder row(Object... cells) {
            rows.add(Arrays.asList(cells));
            return this;
        }

        SectionBuilder total(Object... cells) {
            totals.add(Arrays.asList(cells));
            return this;
        }

        Section build() {
            return new Section(heading, List.copyOf(columns), List.copyOf(rows), List.copyOf(totals));
        }
    }
}
