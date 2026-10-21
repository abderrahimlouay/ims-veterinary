package com.imsvet.ims.common.web;

import com.imsvet.ims.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a Problem Details response from a servlet filter, where the MVC exception handler does not apply
 * (authentication entry point, access-denied handler, forced password change).
 */
@Component
public class ProblemResponseWriter {

    private final JsonMapper json;

    public ProblemResponseWriter(JsonMapper json) {
        this.json = json;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code, String detail)
            throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(),
                Problems.of(code, detail, List.of(), Map.of(), request.getRequestURI()));
    }
}
