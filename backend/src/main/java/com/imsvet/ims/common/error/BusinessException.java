package com.imsvet.ims.common.error;

import java.util.Map;

/**
 * A rule violation reported to the client as Problem Details with a stable {@link ErrorCode}.
 * {@code context} carries machine-readable facts (ids, quantities as strings) for the UI.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> context;

    public BusinessException(ErrorCode code, String detail) {
        this(code, detail, Map.of());
    }

    public BusinessException(ErrorCode code, String detail, Map<String, Object> context) {
        super(detail);
        this.code = code;
        this.context = Map.copyOf(context);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> context() {
        return context;
    }
}
