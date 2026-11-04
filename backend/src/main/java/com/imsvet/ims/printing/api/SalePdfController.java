package com.imsvet.ims.printing.api;

import com.imsvet.ims.printing.application.SalePdfs;
import com.imsvet.ims.printing.application.SalePdfs.Pdf;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** api.md §4.10: {@code GET /api/sales/{id}/pdf?format=a4|ticket}, shown inline for the browser to print. */
@RestController
class SalePdfController {

    private final SalePdfs pdfs;

    SalePdfController(SalePdfs pdfs) {
        this.pdfs = pdfs;
    }

    @GetMapping(value = "/api/sales/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> pdf(@PathVariable long id, @RequestParam(required = false) String format) {
        Pdf pdf = pdfs.render(id, format);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(pdf.fileName()).build()
                        .toString())
                .cacheControl(CacheControl.noStore()).body(pdf.bytes());
    }
}
