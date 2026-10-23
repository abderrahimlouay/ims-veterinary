package com.imsvet.ims.inventory.api;

import com.imsvet.ims.common.paging.PageResponse;
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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** api.md §4.5 {@code /api/inventory}: stock levels, ledger, adjustments and opening stock; permissions on the services. */
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

    private final MovementQueries movements;
    private final StockAdjustments adjustments;
    private final OpeningStock openingStock;
    private final StockLevels stockLevels;

    InventoryController(MovementQueries movements, StockAdjustments adjustments, OpeningStock openingStock,
            StockLevels stockLevels) {
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
