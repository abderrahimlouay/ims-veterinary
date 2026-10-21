package com.imsvet.ims.common.web;

import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The single translation of exceptions into RFC 9457 Problem Details with a business {@code code},
 * {@code traceId}, {@code errors[]} and {@code context} (ADR-020, api.md §2, backend.md §7).
 * Internal details (stack traces, SQL, class names) never reach the response.
 */
@RestControllerAdvice
public class ProblemDetailsHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsHandler.class);

    private static final Set<String> REQUIRED_CONSTRAINTS = Set.of("NotNull", "NotBlank", "NotEmpty");
    /** Context key of a {@link BusinessException} that becomes a {@code Retry-After} header (api.md §1). */
    public static final String RETRY_AFTER_SECONDS = "retryAfterSeconds";
    private static final String NEUTRAL_DETAIL = "An unexpected error occurred. Quote the trace id to support.";

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Object> handleBusiness(BusinessException ex, WebRequest request) {
        if (ex.code().status().is5xxServerError()) {
            log.error("Business failure {}", ex.code(), ex);
        }
        HttpHeaders headers = new HttpHeaders();
        if (ex.context().get(RETRY_AFTER_SECONDS) instanceof Number seconds) {
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(seconds.longValue()));
        }
        return respond(ex.code(), ex.getMessage(), List.of(), ex.context(), headers, request);
    }

    @ExceptionHandler(ValidationException.class)
    ResponseEntity<Object> handleValidation(ValidationException ex, WebRequest request) {
        List<Map<String, Object>> errors = ex.violations().stream()
                .map(v -> {
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("field", v.field());
                    error.put("code", v.code());
                    error.put("message", v.message());
                    return error;
                }).toList();
        return respond(ErrorCode.VALIDATION_FAILED, ex.getMessage(), errors, Map.of(), new HttpHeaders(), request);
    }

    /** Method security denial (BR-SEC-002); CSRF and URL-level denials are written by the security chain. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        return respond(ErrorCode.ACCESS_DENIED, "You do not have the permission for this action.", List.of(),
                Map.of(), new HttpHeaders(), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleAuthentication(AuthenticationException ex, WebRequest request) {
        return respond(ErrorCode.AUTH_REQUIRED, "Authentication is required.", List.of(), Map.of(),
                new HttpHeaders(), request);
    }

    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<Object> handleConcurrency(ConcurrencyFailureException ex, WebRequest request) {
        log.info("Concurrent modification: {}", ex.getClass().getSimpleName());
        return respond(ErrorCode.CONCURRENT_MODIFICATION,
                "The data was changed by someone else or is locked. Reload and try again.",
                List.of(), Map.of(), new HttpHeaders(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        return respond(ErrorCode.INTERNAL_ERROR, NEUTRAL_DETAIL, List.of(), Map.of(), new HttpHeaders(), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.add(fieldError(fe.getField(), fe.getCode(), fe.getDefaultMessage()));
        }
        ex.getBindingResult().getGlobalErrors()
                .forEach(ge -> errors.add(fieldError("", ge.getCode(), ge.getDefaultMessage())));
        return respond(ErrorCode.VALIDATION_FAILED, "The request contains invalid fields.", errors, Map.of(),
                headers, request);
    }

    /** Every other Spring MVC exception: map its HTTP status onto a catalogue code. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ErrorCode code = switch (statusCode.value()) {
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 415 -> ErrorCode.MEDIA_TYPE_NOT_SUPPORTED;
            default -> statusCode.is4xxClientError() ? ErrorCode.VALIDATION_FAILED : ErrorCode.INTERNAL_ERROR;
        };
        String detail;
        if (code == ErrorCode.INTERNAL_ERROR) {
            log.error("Framework failure with status {}", statusCode.value(), ex);
            detail = NEUTRAL_DETAIL;
        } else {
            detail = body instanceof ProblemDetail pd && pd.getDetail() != null ? pd.getDetail() : code.title();
        }
        return respond(code, detail, List.of(), Map.of(), headers, request);
    }

    private ResponseEntity<Object> respond(ErrorCode code, String detail, List<Map<String, Object>> errors,
            Map<String, Object> context, HttpHeaders headers, WebRequest request) {
        String path = request instanceof ServletWebRequest servlet ? servlet.getRequest().getRequestURI() : null;
        ProblemDetail problem = Problems.of(code, detail, errors, context, path);
        return ResponseEntity.status(code.status()).headers(headers).body(problem);
    }

    private static Map<String, Object> fieldError(String field, String constraint, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("field", field);
        error.put("code", errorCodeOf(constraint));
        error.put("message", message);
        return error;
    }

    private static String errorCodeOf(String constraint) {
        if (constraint == null) {
            return "INVALID";
        }
        if (REQUIRED_CONSTRAINTS.contains(constraint)) {
            return "REQUIRED";
        }
        return constraint.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }
}
