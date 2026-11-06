package com.imsvet.ims.common.web;

import java.io.IOException;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * The application serves the built screens itself (ADR-166), from {@code static/} in the release JAR: the hashed
 * {@code assets/} cached for a year, every other path that is not a file answered with {@code index.html} so the
 * screens' own routes work on reload; {@code index.html} is never cached, so a release shows at once. {@code /api}
 * and {@code /actuator} never fall back. Without a built frontend (development, tests) nothing is served here.
 */
@Configuration(proxyBeanMethods = false)
public class FrontendConfiguration implements WebMvcConfigurer {

    private static final String LOCATION = "classpath:/static/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**").addResourceLocations(LOCATION + "assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/**").addResourceLocations(LOCATION)
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new ScreenRoutes());
    }

    /** A file when there is one, else {@code index.html} for a screen route. */
    static final class ScreenRoutes extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource file = location.createRelative(resourcePath);
            if (file.exists() && file.isReadable()) {
                return file;
            }
            if (resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/")
                    || resourcePath.startsWith("assets/")) {
                return null;
            }
            Resource index = location.createRelative("index.html");
            return index.exists() && index.isReadable() ? index : null;
        }
    }
}
