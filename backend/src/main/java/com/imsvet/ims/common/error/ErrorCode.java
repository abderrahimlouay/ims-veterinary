package com.imsvet.ims.common.error;

import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * Stable business error codes; the catalogue is docs/api.md §3 (ADR-020). Clients switch on these, never on text.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    AUTH_ACCOUNT_DISABLED(HttpStatus.FORBIDDEN),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),
    PASSWORD_CHANGE_REQUIRED(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    MEDIA_TYPE_NOT_SUPPORTED(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT),
    PRODUCT_SKU_DUPLICATE(HttpStatus.CONFLICT),
    BARCODE_DUPLICATE(HttpStatus.CONFLICT),
    DOCUMENT_NUMBER_CONFLICT(HttpStatus.CONFLICT),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT),
    DOCUMENT_IMMUTABLE(HttpStatus.CONFLICT),
    RESOURCE_IN_USE(HttpStatus.CONFLICT),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),
    INVENTORY_NOT_OPEN(HttpStatus.CONFLICT),
    INVENTORY_ALREADY_OPEN(HttpStatus.CONFLICT),
    INVENTORY_LINE_ALREADY_REGULARIZED(HttpStatus.CONFLICT),
    CASH_SESSION_ALREADY_OPEN(HttpStatus.CONFLICT),
    CASH_SESSION_REQUIRED(HttpStatus.CONFLICT),
    CASH_SESSION_CLOSED(HttpStatus.CONFLICT),
    PRODUCT_ARCHIVED(HttpStatus.UNPROCESSABLE_CONTENT),
    UNIT_NOT_SELLABLE(HttpStatus.UNPROCESSABLE_CONTENT),
    UNIT_FACTOR_LOCKED(HttpStatus.UNPROCESSABLE_CONTENT),
    LOT_EXPIRED(HttpStatus.UNPROCESSABLE_CONTENT),
    LOT_BLOCKED(HttpStatus.UNPROCESSABLE_CONTENT),
    LOT_RECALLED(HttpStatus.UNPROCESSABLE_CONTENT),
    LOT_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT),
    LOT_PRODUCT_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT),
    LOT_EXPIRY_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT),
    FEFO_VIOLATION(HttpStatus.UNPROCESSABLE_CONTENT),
    STOCK_INSUFFICIENT(HttpStatus.UNPROCESSABLE_CONTENT),
    ADJUSTMENT_REASON_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT),
    RECEIPT_QUANTITY_EXCEEDS_ORDER(HttpStatus.UNPROCESSABLE_CONTENT),
    SUPPLIER_INACTIVE(HttpStatus.UNPROCESSABLE_CONTENT),
    CUSTOMER_INACTIVE(HttpStatus.UNPROCESSABLE_CONTENT),
    CUSTOMER_REQUIRED_FOR_CREDIT(HttpStatus.UNPROCESSABLE_CONTENT),
    CREDIT_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT),
    RETURN_QUANTITY_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT),
    RETURN_NOT_RESTOCKABLE(HttpStatus.UNPROCESSABLE_CONTENT),
    PAYMENT_ALLOCATION_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    LEDGER_MISMATCH(HttpStatus.INTERNAL_SERVER_ERROR),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** Problem Details {@code type}, e.g. {@code urn:ims:problem:stock-insufficient}. */
    public String typeUri() {
        return "urn:ims:problem:" + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Problem Details {@code title}, e.g. {@code Stock insufficient}. */
    public String title() {
        String words = name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
