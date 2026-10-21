package com.imsvet.ims.support;

import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.webmvc.test.autoconfigure.SpringBootMockMvcBuilderCustomizer;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A browser-like API client over MockMvc with the application's real filters (security chain, Spring
 * Session, trace id): keeps cookies, sends the CSRF header like the SPA (ADR-036) and a fixed client IP.
 * Built from the shared test context, so it adds no new context or container (ADR-035).
 */
public final class ApiClient {

    public static final String CSRF_COOKIE = "XSRF-TOKEN";
    public static final String CSRF_HEADER = "X-XSRF-TOKEN";
    public static final String SESSION_COOKIE = "SESSION";

    private final MockMvc mvc;
    private final String clientIp;
    private final Map<String, Cookie> cookies = new LinkedHashMap<>();

    private ApiClient(MockMvc mvc, String clientIp) {
        this.mvc = mvc;
        this.clientIp = clientIp;
    }

    /** One MockMvc per application context: Boot's customizer may register its filters only once. */
    private static final Map<WebApplicationContext, MockMvc> MOCK_MVC = new ConcurrentHashMap<>();

    public static ApiClient create(WebApplicationContext context, String clientIp) {
        return new ApiClient(MOCK_MVC.computeIfAbsent(context, c -> {
            DefaultMockMvcBuilder builder = MockMvcBuilders.webAppContextSetup(c);
            new SpringBootMockMvcBuilderCustomizer(c).customize(builder);
            return builder.build();
        }), clientIp);
    }

    public ResultActions get(String uri) throws Exception {
        return perform(MockMvcRequestBuilders.get(uri), true);
    }

    public ResultActions post(String uri, String json) throws Exception {
        return perform(MockMvcRequestBuilders.post(uri).contentType(MediaType.APPLICATION_JSON).content(json), true);
    }

    public ResultActions postWithoutCsrfHeader(String uri, String json) throws Exception {
        return perform(MockMvcRequestBuilders.post(uri).contentType(MediaType.APPLICATION_JSON).content(json), false);
    }

    public ResultActions login(String username, String password) throws Exception {
        return post("/api/auth/login", "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }

    public Cookie cookie(String name) {
        return cookies.get(name);
    }

    public ResultActions perform(MockHttpServletRequestBuilder request, boolean withCsrfHeader) throws Exception {
        if (withCsrfHeader && !cookies.containsKey(CSRF_COOKIE)) {
            send(MockMvcRequestBuilders.get("/api/auth/me"));
        }
        if (withCsrfHeader) {
            request.header(CSRF_HEADER, cookies.get(CSRF_COOKIE).getValue());
        }
        return send(request);
    }

    private ResultActions send(MockHttpServletRequestBuilder request) throws Exception {
        request.with(r -> {
            r.setRemoteAddr(clientIp);
            return r;
        });
        if (!cookies.isEmpty()) {
            request.cookie(cookies.values().toArray(Cookie[]::new));
        }
        ResultActions actions = mvc.perform(request);
        remember(actions.andReturn());
        return actions;
    }

    private void remember(MvcResult result) {
        MockHttpServletResponse response = result.getResponse();
        for (Cookie cookie : response.getCookies()) {
            if (cookie.getMaxAge() == 0 || cookie.getValue().isEmpty()) {
                cookies.remove(cookie.getName());
            } else {
                cookies.put(cookie.getName(), cookie);
            }
        }
    }
}
