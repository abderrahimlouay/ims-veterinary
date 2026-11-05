package com.imsvet.ims.printing.api;

import com.imsvet.ims.printing.application.ReportPdfs;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** api.md §4.13: every report as a PDF on {@code ?format=pdf} of its JSON path (ADR-149); permissions on the service. */
@RestController
@RequestMapping("/api/reports")
class ReportPdfController {

    private static final String PDF = "format=pdf";

    private final ReportPdfs pdfs;

    ReportPdfController(ReportPdfs pdfs) {
        this.pdfs = pdfs;
    }

    @GetMapping(value = "/revenue", params = PDF, produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> revenue(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String groupBy) {
        return DocumentPdfController.inline(pdfs.revenue(from, to, groupBy));
    }

    @GetMapping(value = "/margin", params = PDF, produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> margin(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String groupBy) {
        return DocumentPdfController.inline(pdfs.margin(from, to, groupBy));
    }

    @GetMapping(value = "/sales-statistics", params = PDF, produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> statistics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String groupBy, @RequestParam(required = false) String customerType) {
        return DocumentPdfController.inline(pdfs.statistics(from, to, groupBy, customerType));
    }

    @GetMapping(value = "/stock-valuation", params = PDF, produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> valuation(@RequestParam(required = false) String basis,
            @RequestParam(required = false) String groupBy) {
        return DocumentPdfController.inline(pdfs.valuation(basis, groupBy));
    }

    @GetMapping(value = "/customer-balances", params = PDF, produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> customerBalances(@RequestParam(required = false) String customerType) {
        return DocumentPdfController.inline(pdfs.customerBalances(customerType));
    }

    @GetMapping(value = "/supplier-payables", params = PDF, produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> supplierPayables(@RequestParam(required = false) Long supplierId) {
        return DocumentPdfController.inline(pdfs.supplierPayables(supplierId));
    }

    @GetMapping(value = "/cash-sessions", params = PDF, produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> cashSessions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long cashierId) {
        return DocumentPdfController.inline(pdfs.cashSessions(from, to, cashierId));
    }

    @GetMapping(value = "/lot-traceability", params = PDF, produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> lotTraceability(@RequestParam long lotId) {
        return DocumentPdfController.inline(pdfs.lotTraceability(lotId));
    }
}
