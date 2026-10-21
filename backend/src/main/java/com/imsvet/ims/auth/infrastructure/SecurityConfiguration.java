package com.imsvet.ims.auth.infrastructure;

import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.security.SecurityLog;
import com.imsvet.ims.common.web.ProblemResponseWriter;
import com.imsvet.ims.users.application.UserAccounts;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * The security chain of security.md and ADR-036: default deny, only login and health are public, server-side
 * sessions with CSRF on every state change, errors as Problem Details, permissions enforced on application
 * services ({@code @PreAuthorize}, BR-SEC-002).
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfiguration {

    /** ADR-039: bcrypt cost 12, behind a delegating encoder so the algorithm can be upgraded later. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(12)));
    }

    /**
     * ADR-036 session cookie, explicit so the flags do not depend on the deployment mode (Boot derives them
     * from {@code server.servlet.session.cookie} only with an embedded server).
     */
    @Bean
    public CookieSerializer sessionCookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("SESSION");
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(true);
        serializer.setSameSite("Strict");
        return serializer;
    }

    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http, UserAccounts accounts, Clock clock,
            ProblemResponseWriter problems,
            @Value("${ims.security.session.absolute-timeout}") Duration absoluteTimeout) {
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookiePath("/");
        csrfTokens.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Strict"));
        http
                .securityContext(c -> c.securityContextRepository(
                        new SessionUserContextRepository(accounts, clock, absoluteTimeout)))
                .csrf(c -> c.spa().csrfTokenRepository(csrfTokens))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) -> problems.write(request, response,
                                ErrorCode.AUTH_REQUIRED, "Authentication is required."))
                        .accessDeniedHandler((request, response, ex) -> {
                            SecurityLog.LOG.atInfo().addKeyValue("event", "access_denied")
                                    .addKeyValue("path", request.getRequestURI()).log("Security event access_denied");
                            problems.write(request, response, ErrorCode.ACCESS_DENIED,
                                    "You do not have the permission for this action.");
                        }))
                .headers(h -> h
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterBefore(new PasswordChangeRequiredFilter(problems), AuthorizationFilter.class);
        return http.build();
    }
}
