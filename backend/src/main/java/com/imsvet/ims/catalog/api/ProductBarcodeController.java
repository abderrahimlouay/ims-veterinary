package com.imsvet.ims.catalog.api;

import com.imsvet.ims.catalog.application.ProductBarcodes;
import com.imsvet.ims.catalog.application.ProductViews.BarcodeView;
import com.imsvet.ims.catalog.application.ProductViews.ScanResult;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** api.md §4.3 barcodes and scan lookup; permissions on the service. */
@RestController
@RequestMapping("/api/products")
class ProductBarcodeController {

    /** The code is kept exactly as sent (ADR-058); the service checks type and format (ADR-067). */
    record BarcodeWriteRequest(String code, String codeType, Long unitId) {
    }

    private final ProductBarcodes barcodes;

    ProductBarcodeController(ProductBarcodes barcodes) {
        this.barcodes = barcodes;
    }

    @GetMapping("/{productId}/barcodes")
    List<BarcodeView> list(@PathVariable long productId) {
        return barcodes.list(productId);
    }

    @PostMapping("/{productId}/barcodes")
    ResponseEntity<BarcodeView> add(@PathVariable long productId, @RequestBody BarcodeWriteRequest body) {
        BarcodeView created = barcodes.add(productId,
                new ProductBarcodes.BarcodeInput(body.code(), body.codeType(), body.unitId()));
        return ResponseEntity.created(URI.create("/api/products/" + productId + "/barcodes/" + created.id()))
                .body(created);
    }

    @DeleteMapping("/{productId}/barcodes/{barcodeId}")
    ResponseEntity<Void> remove(@PathVariable long productId, @PathVariable long barcodeId) {
        barcodes.remove(productId, barcodeId);
        return ResponseEntity.noContent().build();
    }

    /** ADR-071: the code is a query parameter, so codes containing "/" or "%" are found too. */
    @GetMapping("/by-barcode")
    ScanResult scan(@RequestParam String code) {
        return barcodes.scan(code);
    }
}
