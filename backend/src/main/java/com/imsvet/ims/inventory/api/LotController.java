package com.imsvet.ims.inventory.api;

import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.inventory.application.FefoPreview;
import com.imsvet.ims.inventory.application.LotQueries;
import com.imsvet.ims.inventory.application.LotViews.ExpiryAlerts;
import com.imsvet.ims.inventory.application.LotViews.FefoPreviewView;
import com.imsvet.ims.inventory.application.LotViews.LotDetail;
import com.imsvet.ims.inventory.application.LotViews.LotSummary;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * api.md §4.4 lot reads, FEFO preview (§5.3), expiry alerts and the product's lots (§4.3); permissions on the
 * services. No write route (ADR-064).
 */
@RestController
@RequestMapping("/api")
class LotController {

    private final LotQueries lots;
    private final FefoPreview fefo;

    LotController(LotQueries lots, FefoPreview fefo) {
        this.lots = lots;
        this.fefo = fefo;
    }

    /** api.md §5.3; informational, reserves nothing. */
    @GetMapping("/lots/fefo")
    FefoPreviewView fefo(@RequestParam long productId, @RequestParam BigDecimal quantity,
            @RequestParam(required = false) Long unitId) {
        return fefo.preview(productId, quantity, unitId);
    }

    @GetMapping("/lots/expiry-alerts")
    ExpiryAlerts expiryAlerts() {
        return lots.expiryAlerts();
    }

    @GetMapping("/lots")
    PageResponse<LotSummary> search(@RequestParam(required = false) Long productId,
            @RequestParam(required = false) String status, @RequestParam(required = false) Integer expiringWithinDays,
            @RequestParam(defaultValue = "false") boolean hasStock, @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return lots.search(new LotQueries.LotFilter(productId, status, expiringWithinDays, hasStock, q), page, size);
    }

    @GetMapping("/lots/{id}")
    LotDetail get(@PathVariable long id) {
        return lots.get(id);
    }

    @GetMapping("/products/{productId}/lots")
    List<LotSummary> ofProduct(@PathVariable long productId) {
        return lots.ofProduct(productId);
    }
}
