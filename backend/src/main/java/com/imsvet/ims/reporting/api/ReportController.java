package com.imsvet.ims.reporting.api;

import com.imsvet.ims.reporting.application.SalesReports;
import com.imsvet.ims.reporting.application.SalesReports.MarginReport;
import com.imsvet.ims.reporting.application.SalesReports.RevenueReport;
import com.imsvet.ims.reporting.application.SalesReports.StatisticsReport;
import com.imsvet.ims.reporting.application.StockReports;
import com.imsvet.ims.reporting.application.StockReports.ValuationReport;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** api.md §4.13 {@code /api/reports}; permissions on the service. */
@RestController
@RequestMapping("/api/reports")
class ReportController {

    private final SalesReports sales;
    private final StockReports stock;

    ReportController(SalesReports sales, StockReports stock) {
        this.sales = sales;
        this.stock = stock;
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
}
