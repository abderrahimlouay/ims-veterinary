package com.imsvet.ims.reporting.api;

import com.imsvet.ims.reporting.application.FinanceReports;
import com.imsvet.ims.reporting.application.FinanceReports.CashSessionsReport;
import com.imsvet.ims.reporting.application.FinanceReports.CustomerBalancesReport;
import com.imsvet.ims.reporting.application.FinanceReports.PayablesReport;
import com.imsvet.ims.reporting.application.SalesReports;
import com.imsvet.ims.reporting.application.SalesReports.MarginReport;
import com.imsvet.ims.reporting.application.SalesReports.RevenueReport;
import com.imsvet.ims.reporting.application.SalesReports.StatisticsReport;
import com.imsvet.ims.reporting.application.StockReports;
import com.imsvet.ims.reporting.application.StockReports.ValuationReport;
import com.imsvet.ims.reporting.application.TraceabilityReports;
import com.imsvet.ims.reporting.application.TraceabilityReports.LotTraceability;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** api.md §4.13 {@code /api/reports}, as JSON or {@code format=csv} (ADR-149); permissions on the service. */
@RestController
@RequestMapping("/api/reports")
class ReportController {

    private static final String CSV = "format=csv";

    private final SalesReports sales;
    private final StockReports stock;
    private final FinanceReports finance;
    private final TraceabilityReports traceability;
    private final ReportCsv csv;

    ReportController(SalesReports sales, StockReports stock, FinanceReports finance,
            TraceabilityReports traceability, ReportCsv csv) {
        this.csv = csv;
        this.sales = sales;
        this.stock = stock;
        this.finance = finance;
        this.traceability = traceability;
    }

    @GetMapping("/revenue")
    RevenueReport revenue(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String groupBy) {
        return sales.revenue(from, to, groupBy);
    }

    @GetMapping("/margin")
    MarginReport margin(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String groupBy) {
        return sales.margin(from, to, groupBy);
    }

    @GetMapping("/sales-statistics")
    StatisticsReport statistics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String groupBy, @RequestParam(required = false) String customerType) {
        return sales.statistics(from, to, groupBy, customerType);
    }

    @GetMapping("/stock-valuation")
    ValuationReport valuation(@RequestParam(required = false) String basis,
            @RequestParam(required = false) String groupBy) {
        return stock.valuation(basis, groupBy);
    }

    @GetMapping("/customer-balances")
    CustomerBalancesReport customerBalances(@RequestParam(required = false) String customerType) {
        return finance.customerBalances(customerType);
    }

    @GetMapping("/supplier-payables")
    PayablesReport supplierPayables(@RequestParam(required = false) Long supplierId) {
        return finance.supplierPayables(supplierId);
    }

    @GetMapping("/cash-sessions")
    CashSessionsReport cashSessions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long cashierId) {
        return finance.cashSessions(from, to, cashierId);
    }

    @GetMapping("/lot-traceability")
    LotTraceability lotTraceability(@RequestParam long lotId) {
        return traceability.lot(lotId);
    }

    @GetMapping(value = "/revenue", params = CSV)
    ResponseEntity<byte[]> revenueCsv(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @RequestParam(required = false) String groupBy) {
        return csv.revenue(sales.revenue(from, to, groupBy));
    }

    @GetMapping(value = "/margin", params = CSV)
    ResponseEntity<byte[]> marginCsv(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @RequestParam(required = false) String groupBy) {
        return csv.margin(sales.margin(from, to, groupBy));
    }

    @GetMapping(value = "/sales-statistics", params = CSV)
    ResponseEntity<byte[]> statisticsCsv(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) String customerType) {
        return csv.statistics(sales.statistics(from, to, groupBy, customerType));
    }

    @GetMapping(value = "/stock-valuation", params = CSV)
    ResponseEntity<byte[]> valuationCsv(@RequestParam(required = false) String basis,
            @RequestParam(required = false) String groupBy) {
        return csv.valuation(stock.valuation(basis, groupBy));
    }

    @GetMapping(value = "/customer-balances", params = CSV)
    ResponseEntity<byte[]> customerBalancesCsv(@RequestParam(required = false) String customerType) {
        return csv.customerBalances(finance.customerBalances(customerType));
    }

    @GetMapping(value = "/supplier-payables", params = CSV)
    ResponseEntity<byte[]> supplierPayablesCsv(@RequestParam(required = false) Long supplierId) {
        return csv.supplierPayables(finance.supplierPayables(supplierId));
    }

    @GetMapping(value = "/cash-sessions", params = CSV)
    ResponseEntity<byte[]> cashSessionsCsv(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @RequestParam(required = false) Long cashierId) {
        return csv.cashSessions(finance.cashSessions(from, to, cashierId));
    }

    @GetMapping(value = "/lot-traceability", params = CSV)
    ResponseEntity<byte[]> lotTraceabilityCsv(@RequestParam long lotId) {
        return csv.lotTraceability(traceability.lot(lotId));
    }
}
