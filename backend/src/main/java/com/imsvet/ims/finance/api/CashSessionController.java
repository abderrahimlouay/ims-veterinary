package com.imsvet.ims.finance.api;

import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.finance.application.CashSessionView;
import com.imsvet.ims.finance.application.CashSessions;
import java.math.BigDecimal;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** api.md §4.12 {@code /api/cash-sessions}; permissions on the service. */
@RestController
@RequestMapping("/api/cash-sessions")
class CashSessionController {

    record OpenRequest(BigDecimal openingAmount) {
    }

    record CloseRequest(BigDecimal countedAmount) {
    }

    private final CashSessions sessions;

    CashSessionController(CashSessions sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/open")
    ResponseEntity<CashSessionView> open(@RequestBody OpenRequest body) {
        CashSessionView opened = sessions.open(body.openingAmount());
        return ResponseEntity.created(URI.create("/api/cash-sessions/" + opened.id())).body(opened);
    }

    /** 204 when the signed-in user has no open session. */
    @GetMapping("/current")
    ResponseEntity<CashSessionView> current() {
        return sessions.current().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/close")
    CashSessionView close(@PathVariable long id, @RequestBody CloseRequest body) {
        return sessions.close(id, body.countedAmount());
    }

    @GetMapping
    PageResponse<CashSessionView> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return sessions.list(page, size);
    }

    @GetMapping("/{id}")
    CashSessionView get(@PathVariable long id) {
        return sessions.get(id);
    }
}
