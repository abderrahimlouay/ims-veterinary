package com.imsvet.ims.common.error;

import java.util.List;

/**
 * A {@code VALIDATION_FAILED} found by a service rather than by bean validation; carries the same
 * {@code errors[]} entries ({@code field}, {@code code}, {@code message}) as api.md §2.
 */
public class ValidationException extends BusinessException {

    public record Violation(String field, String code, String message) {
    }

    private final List<Violation> violations;

    public ValidationException(String detail, List<Violation> violations) {
        super(ErrorCode.VALIDATION_FAILED, detail);
        this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() {
        return violations;
    }
}
