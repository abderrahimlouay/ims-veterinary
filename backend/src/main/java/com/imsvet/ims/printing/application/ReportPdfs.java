package com.imsvet.ims.printing.application;

import com.imsvet.ims.common.time.BusinessSettings;
import com.imsvet.ims.printing.application.PdfRenderer.Paper;
import com.imsvet.ims.printing.application.SalePdfs.Pdf;
import com.imsvet.ims.reporting.application.FinanceReports;
import com.imsvet.ims.reporting.application.ReportTable;
import com.imsvet.ims.reporting.application.ReportTable.Column;
import com.imsvet.ims.reporting.application.ReportTable.Section;
import com.imsvet.ims.reporting.application.ReportTables;
import com.imsvet.ims.reporting.application.SalesReports;
import com.imsvet.ims.reporting.application.StockReports;
import com.imsvet.ims.reporting.application.TraceabilityReports;
import com.imsvet.ims.settings.application.CompanyIdentity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reports as A4 PDFs (ADR-149, api.md §4.13 {@code format=pdf}): the export layout of {@link ReportTables} under
 * the company header, with the filters and the print date; landscape for the wide reports. Each report is read through
 * its own service, so the permission is the report's (BR-RPT-003); the same expressions guard these methods.
 */
@Service
public class ReportPdfs {

    private static final int MONEY_SCALE = 2;

    private final SalesReports sales;
    private final StockReports stock;
    private final FinanceReports finance;
    private final TraceabilityReports traceability;
    private final ReportTables tables;
    private final CompanyIdentity identity;
    private final PdfRenderer renderer;
    private final BusinessSettings business;
    private final Clock clock;

    public ReportPdfs(SalesReports sales, StockReports stock, FinanceReports finance,
            TraceabilityReports traceability, ReportTables tables, CompanyIdentity identity, PdfRenderer renderer,
            BusinessSettings business, Clock clock) {
        this.sales = sales;
        this.stock = stock;
        this.finance = finance;
        this.traceability = traceability;
        this.tables = tables;
        this.identity = identity;
        this.renderer = renderer;
        this.business = business;
        this.clock = clock;
    }

    @PreAuthorize("hasAuthority('report.sales')")
    @Transactional(readOnly = true)
    public Pdf revenue(LocalDate from, LocalDate to, String groupBy) {
        return render(tables.revenue(sales.revenue(from, to, groupBy)));
    }

    @PreAuthorize("hasAuthority('report.margin')")
    @Transactional(readOnly = true)
    public Pdf margin(LocalDate from, LocalDate to, String groupBy) {
        return render(tables.margin(sales.margin(from, to, groupBy)));
    }

    @PreAuthorize("hasAuthority('report.sales')")
    @Transactional(readOnly = true)
    public Pdf statistics(LocalDate from, LocalDate to, String groupBy, String customerType) {
        return render(tables.statistics(sales.statistics(from, to, groupBy, customerType)));
    }

    @PreAuthorize("hasAuthority('report.stock') and (#basis != 'cost' or hasAuthority('report.margin'))")
    @Transactional(readOnly = true)
    public Pdf valuation(String basis, String groupBy) {
        return render(tables.valuation(stock.valuation(basis, groupBy)));
    }

    @PreAuthorize("hasAuthority('report.finance')")
    @Transactional(readOnly = true)
    public Pdf customerBalances(String customerType) {
        return render(tables.customerBalances(finance.customerBalances(customerType)));
    }

    @PreAuthorize("hasAuthority('report.finance')")
    @Transactional(readOnly = true)
    public Pdf supplierPayables(Long supplierId) {
        return render(tables.supplierPayables(finance.supplierPayables(supplierId)));
    }

    @PreAuthorize("hasAuthority('report.finance')")
    @Transactional(readOnly = true)
    public Pdf cashSessions(LocalDate from, LocalDate to, Long cashierId) {
        return render(tables.cashSessions(finance.cashSessions(from, to, cashierId)));
    }

    @PreAuthorize("hasAuthority('lot.recall')")
    @Transactional(readOnly = true)
    public Pdf lotTraceability(long lotId) {
        return render(tables.lotTraceability(traceability.lot(lotId)));
    }

    private Pdf render(ReportTable table) {
        ZoneId zone = business.timeZone();
        Map<String, Object> model = new HashMap<>();
        model.put("company", PrintFormats.company(identity.current().orElse(null)));
        model.put("printedAt", PrintFormats.dateTime(clock.instant(), zone));
        model.put("title", table.title());
        model.put("wide", table.wide());
        model.put("filters", table.filters());
        model.put("sections", table.sections().stream().map(s -> section(s, zone)).toList());
        return new Pdf(renderer.render("report-a4", model, Paper.A4), table.fileName() + ".pdf");
    }

    private static Map<String, Object> section(Section s, ZoneId zone) {
        Map<String, Object> section = new HashMap<>();
        section.put("heading", s.heading());
        section.put("columns", s.columns().stream().map(c -> Map.of("label", c.label(), "num", c.numeric())).toList());
        section.put("rows", s.rows().stream().map(r -> cells(r, s.columns(), zone)).toList());
        section.put("totals", s.totals().stream().map(r -> cells(r, s.columns(), zone)).toList());
        return section;
    }

    private static List<Map<String, Object>> cells(List<Object> row, List<Column> columns, ZoneId zone) {
        List<Map<String, Object>> cells = new ArrayList<>();
        for (int i = 0; i < row.size(); i++) {
            boolean numeric = i < columns.size() && columns.get(i).numeric();
            cells.add(Map.of("text", text(row.get(i), zone), "num", numeric));
        }
        return cells;
    }

    private static String text(Object value, ZoneId zone) {
        return switch (value) {
            case null -> "";
            case BigDecimal number when number.scale() == MONEY_SCALE -> PrintFormats.money(number);
            case BigDecimal number -> PrintFormats.quantity(number);
            case LocalDate date -> PrintFormats.date(date);
            case Instant instant -> PrintFormats.dateTime(instant, zone);
            default -> value.toString();
        };
    }
}
