package com.imsvet.ims.inventory.api;

import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.inventory.application.LotViews.MovementEntry;
import com.imsvet.ims.inventory.application.MovementQueries;
import com.imsvet.ims.inventory.application.MovementQueries.MovementFilter;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** api.md §4.5 {@code /api/inventory}: the ledger in this slice; permissions on the services. */
@RestController
@RequestMapping("/api/inventory")
class InventoryController {

    private final MovementQueries movements;

    InventoryController(MovementQueries movements) {
        this.movements = movements;
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
