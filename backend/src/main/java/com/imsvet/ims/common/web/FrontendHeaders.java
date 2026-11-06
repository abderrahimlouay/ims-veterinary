package com.imsvet.ims.common.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The security headers of the screens (ADR-157, ADR-166): {@code frontend/src/securityHeaders.json}, copied into the
 * JAR by the build, is the single source shared with the frontend's preview, under which the end-to-end journeys run
 * (J13). The API keeps its own stricter policy.
 */
@Component
public class FrontendHeaders {

    static final String LOCATION = "frontend/securityHeaders.json";

    private final Map<String, String> headers;

    public FrontendHeaders(JsonMapper json) {
        try (InputStream in = new ClassPathResource(LOCATION).getInputStream()) {
            this.headers = Collections.unmodifiableMap(json.readValue(in, new TypeReference<LinkedHashMap<String, String>>() {
            }));
        } catch (IOException e) {
            throw new UncheckedIOException("The screens' security headers (" + LOCATION + ") are missing", e);
        }
    }

    /** Header name to value, in the file's order. */
    public Map<String, String> all() {
        return headers;
    }
}
