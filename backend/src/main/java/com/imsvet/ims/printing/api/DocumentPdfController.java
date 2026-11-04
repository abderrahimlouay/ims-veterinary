package com.imsvet.ims.printing.api;

import com.imsvet.ims.printing.application.PurchaseOrderPdfs;
import com.imsvet.ims.printing.application.RecallPdfs;
import com.imsvet.ims.printing.application.SalePdfs;
import com.imsvet.ims.printing.application.SalePdfs.Pdf;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * api.md: the credit note of a customer return, a purchase order and a recall buyers list as PDFs (ADR-143). The
 * recall list answers `?format=pdf` on the same path as its JSON report.
 */
@RestController
class DocumentPdfController {

    private final SalePdfs sales;
    private final PurchaseOrderPdfs orders;
    private final RecallPdfs recalls;

    DocumentPdfController(SalePdfs sales, PurchaseOrderPdfs orders, RecallPdfs recalls) {
        this.sales = sales;
        this.orders = orders;
        this.recalls = recalls;
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

    static ResponseEntity<byte[]> inline(Pdf pdf) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(pdf.fileName()).build()
                        .toString())
                .cacheControl(CacheControl.noStore()).body(pdf.bytes());
    }
}
