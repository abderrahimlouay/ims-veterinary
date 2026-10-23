package com.imsvet.ims.inventory.api;

import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.inventory.application.CountQueries;
import com.imsvet.ims.inventory.application.CountRegularisation;
import com.imsvet.ims.inventory.application.CountViews.CountDetail;
import com.imsvet.ims.inventory.application.CountViews.CountSummary;
import com.imsvet.ims.inventory.application.CountViews.Line;
import com.imsvet.ims.inventory.application.InventoryCounts;
import com.imsvet.ims.inventory.application.ReorderGenerator.GenerationResult;
import com.imsvet.ims.inventory.application.ReorderProposals;
import com.imsvet.ims.inventory.application.ReorderProposals.ProposalEntry;
import com.imsvet.ims.inventory.application.IntegrityChecks;
import com.imsvet.ims.inventory.application.IntegrityChecks.IntegrityRun;
import com.imsvet.ims.inventory.application.LotViews.MovementEntry;
import com.imsvet.ims.inventory.application.MovementQueries;
import com.imsvet.ims.inventory.application.MovementQueries.MovementFilter;
import com.imsvet.ims.inventory.application.LotViews.OpeningResult;
import com.imsvet.ims.inventory.application.OpeningStock;
import com.imsvet.ims.inventory.application.StockAdjustments;
import com.imsvet.ims.inventory.application.StockLevels;
import com.imsvet.ims.inventory.application.StockLevels.StockLevel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * api.md §4.5 {@code /api/inventory}: stock levels, ledger, adjustments, opening stock, counts and the integrity
 * check; permissions on the services.
 */
@RestController
@RequestMapping("/api/inventory")
class InventoryController {

    /** ADR-085: the reason is checked by the service ({@code ADJUSTMENT_REASON_REQUIRED}); only its length here. */
    record AdjustmentRequest(@NotNull Long lotId, String type, BigDecimal quantity, @Size(max = 500) String reason) {
    }

    /** ADR-083: required fields are checked by the service, with their field codes. */
    record OpeningRequest(@NotNull Long productId, String lotNumber, LocalDate expiryDate, BigDecimal quantity,
            Long unitId, BigDecimal unitCost) {
    }

    /** ADR-073: type and scope are checked by the service, with their field codes. */
    record OpenCountRequest(String type, Integer categoryId, List<Long> productIds, @Size(max = 1000) String notes) {
    }

    record CountedRequest(BigDecimal countedQty) {
    }

    /** ADR-075: omitted or empty, every counted line. */
    record RegularizeRequest(List<Long> lineIds) {
    }

    /** ADR-074: {@code lotId}, or the fields of a lot unknown to the system; checked by the service. */
    record AddLineRequest(Long lotId, Long productId, String lotNumber, LocalDate expiryDate, BigDecimal unitCost,
            BigDecimal countedQty) {
    }

    private final MovementQueries movements;
    private final StockAdjustments adjustments;
    private final OpeningStock openingStock;
    private final StockLevels stockLevels;
    private final IntegrityChecks integrityChecks;
    private final InventoryCounts inventoryCounts;
    private final CountQueries countQueries;
    private final CountRegularisation regularisation;
    private final ReorderProposals reorderProposals;

    InventoryController(MovementQueries movements, StockAdjustments adjustments, OpeningStock openingStock,
            StockLevels stockLevels, IntegrityChecks integrityChecks, InventoryCounts inventoryCounts,
            CountQueries countQueries, CountRegularisation regularisation, ReorderProposals reorderProposals) {
        this.reorderProposals = reorderProposals;
        this.regularisation = regularisation;
        this.integrityChecks = integrityChecks;
        this.inventoryCounts = inventoryCounts;
        this.countQueries = countQueries;
        this.movements = movements;
        this.adjustments = adjustments;
        this.openingStock = openingStock;
        this.stockLevels = stockLevels;
    }

    @PostMapping("/opening-stock")
    ResponseEntity<OpeningResult> openingStock(@Valid @RequestBody OpeningRequest body) {
        OpeningResult result = openingStock.enter(new OpeningStock.OpeningInput(body.productId(), body.lotNumber(),
                body.expiryDate(), body.quantity(), body.unitId(), body.unitCost()));
        return ResponseEntity.created(URI.create("/api/lots/" + result.lot().id())).body(result);
    }

    @PostMapping("/adjustments")
    ResponseEntity<MovementEntry> adjust(@Valid @RequestBody AdjustmentRequest body) {
        MovementEntry created = adjustments.adjust(
                new StockAdjustments.AdjustmentInput(body.lotId(), body.type(), body.quantity(), body.reason()));
        return ResponseEntity.created(URI.create("/api/inventory/movements/" + created.id())).body(created);
    }

    @GetMapping("/counts")
    PageResponse<CountSummary> counts(@RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return countQueries.search(status, page, size);
    }

    @PostMapping("/counts")
    ResponseEntity<CountDetail> openCount(@Valid @RequestBody OpenCountRequest body) {
        CountDetail opened = inventoryCounts.open(new InventoryCounts.OpenInput(body.type(), body.categoryId(),
                body.productIds(), body.notes()));
        return ResponseEntity.created(URI.create("/api/inventory/counts/" + opened.id())).body(opened);
    }

    @GetMapping("/counts/{id}")
    CountDetail count(@PathVariable long id) {
        return countQueries.detail(id);
    }

    @PutMapping("/counts/{id}/lines/{lineId}")
    Line enterCounted(@PathVariable long id, @PathVariable long lineId, @RequestBody CountedRequest body) {
        return inventoryCounts.enter(id, lineId, body.countedQty());
    }

    @PostMapping("/counts/{id}/lines")
    ResponseEntity<Line> addLine(@PathVariable long id, @RequestBody AddLineRequest body) {
        Line added = inventoryCounts.add(id, new InventoryCounts.AddInput(body.lotId(), body.productId(),
                body.lotNumber(), body.expiryDate(), body.unitCost(), body.countedQty()));
        return ResponseEntity.created(URI.create("/api/inventory/counts/" + id)).body(added);
    }

    @PostMapping("/counts/{id}/regularize")
    CountDetail regularize(@PathVariable long id, @RequestBody(required = false) RegularizeRequest body) {
        return regularisation.regularize(id, body == null ? null : body.lineIds());
    }

    @PostMapping("/counts/{id}/cancel")
    CountDetail cancelCount(@PathVariable long id) {
        return inventoryCounts.cancel(id);
    }

    @GetMapping("/reorder-proposals")
    PageResponse<ProposalEntry> reorderProposals(@RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return reorderProposals.search(status, page, size);
    }

    @PostMapping("/reorder-proposals/generate")
    GenerationResult generateReorderProposals() {
        return reorderProposals.generateNow();
    }

    @PostMapping("/reorder-proposals/{id}/dismiss")
    ProposalEntry dismissReorderProposal(@PathVariable long id) {
        return reorderProposals.dismiss(id);
    }

    /** ADR-086: 204 when no run exists yet. */
    @GetMapping("/integrity-check")
    ResponseEntity<IntegrityRun> latestIntegrityRun() {
        return integrityChecks.latest().map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/integrity-check")
    IntegrityRun runIntegrityCheck() {
        return integrityChecks.runNow();
    }

    @GetMapping("/stock")
    PageResponse<StockLevel> stock(@RequestParam(required = false) String q,
            @RequestParam(required = false) Integer categoryId, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean belowReorder,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return stockLevels.search(new StockLevels.StockFilterInput(q, categoryId, status, belowReorder, includeArchived),
                page, size);
    }

    @GetMapping("/movements")
    PageResponse<MovementEntry> movements(@RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long lotId, @RequestParam(required = false) String type,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return movements.search(new MovementFilter(productId, lotId, type, userId, from, to), page, size);
    }
}
