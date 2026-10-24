package com.imsvet.ims.purchasing.api;

import com.imsvet.ims.purchasing.application.OrderViews.PurchaseOrderDetail;
import com.imsvet.ims.purchasing.application.ReorderConversion;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** api.md §4.5 conversion of reorder proposals into draft purchase orders (ADR-102); it belongs to purchasing. */
@RestController
class ReorderConversionController {

    record GroupRequest(Long supplierId, List<Long> proposalIds) {
    }

    record ConvertRequest(List<GroupRequest> groups) {
    }

    private final ReorderConversion conversion;

    ReorderConversionController(ReorderConversion conversion) {
        this.conversion = conversion;
    }

    @PostMapping("/api/inventory/reorder-proposals/convert")
    @ResponseStatus(HttpStatus.CREATED)
    List<PurchaseOrderDetail> convert(@RequestBody ConvertRequest body) {
        return conversion.convert(body.groups() == null ? null : body.groups().stream()
                .map(g -> new ReorderConversion.Group(g.supplierId(), g.proposalIds())).toList());
    }
}
