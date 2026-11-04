package com.imsvet.ims.printing.api;

import com.imsvet.ims.printing.application.CashReportPdfs;
import com.imsvet.ims.printing.application.PurchaseOrderPdfs;
import com.imsvet.ims.printing.application.RecallPdfs;
import com.imsvet.ims.printing.application.SalePdfs;
import com.imsvet.ims.printing.application.SalePdfs.Pdf;
import com.imsvet.ims.printing.application.StatementPdfs;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * api.md: the credit note of a customer return, a purchase order, a recall buyers list, a customer statement and a
 * cash-session report as PDFs (ADR-137, ADR-143). The last three answer `?format=pdf` on the path of their JSON view.
 */
@RestController
class DocumentPdfController {

    private final SalePdfs sales;
    private final PurchaseOrderPdfs orders;
    private final RecallPdfs recalls;
    private final StatementPdfs statements;
    private final CashReportPdfs cash;

    DocumentPdfController(SalePdfs sales, PurchaseOrderPdfs orders, RecallPdfs recalls, StatementPdfs statements,
            CashReportPdfs cash) {
        this.sales = sales;
        this.orders = orders;
        this.recalls = recalls;
        this.statements = statements;
        this.cash = cash;
    }

    @GetMapping(value = "/api/returns/customer/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> creditNote(@PathVariable long id) {
        return inline(sales.renderReturn(id));
    }

    @GetMapping(value = "/api/purchase-orders/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> purchaseOrder(@PathVariable long id) {
        return inline(orders.render(id));
    }

    @GetMapping(value = "/api/lots/{id}/recall-report", params = "format=pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> recall(@PathVariable long id) {
        return inline(recalls.render(id));
    }

    @GetMapping(value = "/api/customers/{id}/statement", params = "format=pdf",
            produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> statement(@PathVariable long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return inline(statements.render(id, from, to));
    }

    @GetMapping(value = "/api/cash-sessions/{id}/report", params = "format=pdf",
            produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> cashReport(@PathVariable long id) {
        return inline(cash.render(id));
    }

    static ResponseEntity<byte[]> inline(Pdf pdf) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(pdf.fileName()).build()
                        .toString())
                .cacheControl(CacheControl.noStore()).body(pdf.bytes());
    }
}
