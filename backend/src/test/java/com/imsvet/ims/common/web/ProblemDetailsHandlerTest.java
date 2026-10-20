package com.imsvet.ims.common.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.common.json.DecimalJsonConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * API-level contract of error responses (api.md §1-§3, ADR-020, ADR-021, ADR-031).
 */
@WebMvcTest(controllers = ProblemDetailsTestController.class)
@Import({ProblemDetailsHandler.class, TraceIdFilter.class, DecimalJsonConfiguration.class})
class ProblemDetailsHandlerTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private MockMvc mvc;

    @Test
    void businessExceptionBecomesProblemDetailsWithCodeTraceIdAndContext() throws Exception {
        MvcResult result = mvc.perform(get("/api/test-problems/business"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.type").value("urn:ims:problem:stock-insufficient"))
                .andExpect(jsonPath("$.title").value("Stock insufficient"))
                .andExpect(jsonPath("$.detail").value("Not enough stock in lot L2407A."))
                .andExpect(jsonPath("$.instance").value("/api/test-problems/business"))
                .andExpect(jsonPath("$.context.lotId").value(88))
                .andExpect(jsonPath("$.context.availableBase").value("9.000"))
                .andExpect(jsonPath("$.errors").isEmpty())
                .andExpect(header().string(TraceIdFilter.HEADER, not(emptyString())))
                .andReturn();

        String traceId = result.getResponse().getHeader(TraceIdFilter.HEADER);
        mvc.perform(get("/api/test-problems/business").header(TraceIdFilter.HEADER, traceId))
                .andExpect(jsonPath("$.traceId").value(traceId));
    }

    @Test
    void beanValidationFailureListsFieldErrors() throws Exception {
        mvc.perform(post("/api/test-problems/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lotNumber\":\"\",\"quantity\":\"-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'lotNumber')].code").value("REQUIRED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'quantity')].code").value("POSITIVE"))
                .andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    void malformedJsonIsAValidationFailure() throws Exception {
        mvc.perform(post("/api/test-problems/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lotNumber\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void unknownPathIsNotFound() throws Exception {
        mvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void unsupportedMethodIsMethodNotAllowed() throws Exception {
        mvc.perform(delete("/api/test-problems/business"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsupportedMediaTypeIsReported() throws Exception {
        mvc.perform(post("/api/test-problems/validated").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("MEDIA_TYPE_NOT_SUPPORTED"));
    }

    @Test
    void optimisticLockFailureIsConcurrentModification() throws Exception {
        mvc.perform(get("/api/test-problems/stale"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void unexpectedFailureLeaksNoInternals() throws Exception {
        mvc.perform(get("/api/test-problems/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(content().string(not(containsString("SELECT"))))
                .andExpect(content().string(not(containsString("com.imsvet"))))
                .andExpect(content().string(not(containsString("Exception"))));
    }

    @Test
    void decimalsInResponsesAreStrings() throws Exception {
        mvc.perform(get("/api/test-problems/price"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"price\":\"1234.50\"}", JsonCompareMode.STRICT));
    }

    @Test
    void wellFormedIncomingTraceIdIsEchoed() throws Exception {
        mvc.perform(get("/api/test-problems/price").header(TraceIdFilter.HEADER, "abc-123"))
                .andExpect(header().string(TraceIdFilter.HEADER, "abc-123"));
    }

    @Test
    void malformedIncomingTraceIdIsReplaced() throws Exception {
        mvc.perform(get("/api/test-problems/price").header(TraceIdFilter.HEADER, "bad id\r\ninjected"))
                .andExpect(header().string(TraceIdFilter.HEADER, matchesPattern("[0-9a-f]{32}")));
    }
}
