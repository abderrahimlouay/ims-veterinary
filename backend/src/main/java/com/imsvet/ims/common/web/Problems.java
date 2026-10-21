package com.imsvet.ims.common.web;

import com.imsvet.ims.common.error.ErrorCode;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.ProblemDetail;

/**
 * Builds the Problem Details body of api.md §2, for the MVC handler and for the security filters alike.
 */
public final class Problems {

    private Problems() {
    }

    public static ProblemDetail of(ErrorCode code, String detail, List<Map<String, Object>> errors,
            Map<String, Object> context, String path) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(code.typeUri()));
        problem.setTitle(code.title());
        if (path != null) {
            problem.setInstance(URI.create(path));
        }
        problem.setProperty("code", code.name());
        problem.setProperty("traceId", TraceIdFilter.current());
        problem.setProperty("errors", errors);
        problem.setProperty("context", context);
        return problem;
    }
}
