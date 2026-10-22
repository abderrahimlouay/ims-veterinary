package com.imsvet.ims.inventory.api;

import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.inventory.application.LotQueries;
import com.imsvet.ims.inventory.application.LotViews.LotDetail;
import com.imsvet.ims.inventory.application.LotViews.LotSummary;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** api.md §4.4 lot reads and the product's lots (§4.3); permissions on the service. No write route (ADR-064). */
@RestController
@RequestMapping("/api")
class LotController {

    private final LotQueries lots;

    LotController(LotQueries lots) {
        this.lots = lots;
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
