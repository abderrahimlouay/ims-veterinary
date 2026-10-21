package com.imsvet.ims.audit.api;

import com.imsvet.ims.audit.application.AuditEntryView;
import com.imsvet.ims.audit.application.AuditLogFilter;
import com.imsvet.ims.audit.application.AuditLogQuery;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.common.paging.PageResponse;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/audit-logs} (api.md §4.14), read-only; permission {@code audit.read} on the service.
 */
@RestController
@RequestMapping("/api/audit-logs")
class AuditLogController {

    private final AuditLogQuery query;

    AuditLogController(AuditLogQuery query) {
        this.query = query;
    }

    @GetMapping
    PageResponse<AuditEntryView> search(@RequestParam(required = false) String entity,
            @RequestParam(required = false) Long entityId, @RequestParam(required = false) Long userId,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return query.search(new AuditLogFilter(entity, entityId, userId, action, from, to), page, size);
    }
}
