package com.imsvet.ims.inventory.api;

import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.inventory.application.FefoPreview;
import com.imsvet.ims.inventory.application.LotActions;
import com.imsvet.ims.inventory.application.LotQueries;
import com.imsvet.ims.inventory.application.LotViews.ExpiryAlerts;
import com.imsvet.ims.inventory.application.LotViews.FefoPreviewView;
import com.imsvet.ims.inventory.application.LotViews.LotDetail;
import com.imsvet.ims.inventory.application.LotViews.LotSummary;
import com.imsvet.ims.inventory.application.LotViews.RecallReport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * api.md §4.4 lot reads, FEFO preview (§5.3), expiry alerts and the product's lots (§4.3); permissions on the
 * services. Block, unblock and recall change the stored status only; no route creates or edits a lot (ADR-064).
 */
@RestController
@RequestMapping("/api")
class LotController {

    /** A mandatory reason for block, unblock and recall (ADR-060, W6). */
    record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    private final LotQueries lots;
    private final FefoPreview fefo;
    private final LotActions actions;

    LotController(LotQueries lots, FefoPreview fefo, LotActions actions) {
        this.lots = lots;
        this.fefo = fefo;
        this.actions = actions;
    }

    @PostMapping("/lots/{id}/block")
    LotDetail block(@PathVariable long id, @Valid @RequestBody ReasonRequest body) {
        return actions.block(id, body.reason().trim());
    }

    @PostMapping("/lots/{id}/unblock")
    LotDetail unblock(@PathVariable long id, @Valid @RequestBody ReasonRequest body) {
        return actions.unblock(id, body.reason().trim());
    }

    /** api.md §5.7; repeating it on a recalled lot returns the same report. */
    @PostMapping("/lots/{id}/recall")
    RecallReport recall(@PathVariable long id, @Valid @RequestBody ReasonRequest body) {
        return actions.recall(id, body.reason().trim());
    }

    @GetMapping("/lots/{id}/recall-report")
    RecallReport recallReport(@PathVariable long id) {
        return actions.recallReport(id);
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
