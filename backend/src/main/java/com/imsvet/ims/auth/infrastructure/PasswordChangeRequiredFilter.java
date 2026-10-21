package com.imsvet.ims.auth.infrastructure;

import com.imsvet.ims.auth.application.SessionUser;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.web.ProblemResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * ADR-039: a user holding a temporary password may only read their profile, change the password or log
 * out; any other request is refused with {@code PASSWORD_CHANGE_REQUIRED}. Part of the security chain only
 * (not a servlet filter bean).
 */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    private static final List<RequestMatcher> ALLOWED = List.of(
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/auth/me"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/change-password"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/logout"));

    private final ProblemResponseWriter problems;

    public PasswordChangeRequiredFilter(ProblemResponseWriter problems) {
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SessionUser user
                && user.account().mustChangePassword()
                && ALLOWED.stream().noneMatch(m -> m.matches(request))) {
            problems.write(request, response, ErrorCode.PASSWORD_CHANGE_REQUIRED,
                    "Change your temporary password first.");
            return;
        }
        chain.doFilter(request, response);
    }
}
