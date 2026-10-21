package com.imsvet.ims.common.web;

import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints that raise each kind of failure handled by {@link ProblemDetailsHandler}.
 */
@RestController
@RequestMapping("/api/test-problems")
class ProblemDetailsTestController {

    record LineRequest(@NotBlank String lotNumber, @Positive BigDecimal quantity) {
    }

    record PriceResponse(BigDecimal price) {
    }

    @GetMapping("/business")
    void business() {
        throw new BusinessException(ErrorCode.STOCK_INSUFFICIENT, "Not enough stock in lot L2407A.",
                Map.of("lotId", 88, "availableBase", "9.000"));
    }

    @PostMapping("/validated")
    void validated(@Valid @RequestBody LineRequest request) {
    }

    @GetMapping("/stale")
    void stale() {
        throw new ObjectOptimisticLockingFailureException("Product", 1L);
    }

    @GetMapping("/unexpected")
    void unexpected() {
        throw new IllegalStateException("SELECT password_hash FROM users failed at com.imsvet.Secret");
    }

    @GetMapping("/forbidden")
    void forbidden() {
        throw new AccessDeniedException("denied by method security");
    }

    @GetMapping("/rate-limited")
    void rateLimited() {
        throw new BusinessException(ErrorCode.RATE_LIMITED, "Too many attempts.",
                Map.of(ProblemDetailsHandler.RETRY_AFTER_SECONDS, 60L));
    }

    @GetMapping("/field-violation")
    void fieldViolation() {
        throw new ValidationException("Invalid.",
                List.of(new ValidationException.Violation("newPassword", "TOO_SHORT", "Too short.")));
    }

    @GetMapping("/price")
    PriceResponse price() {
        return new PriceResponse(new BigDecimal("1234.50"));
    }
}
