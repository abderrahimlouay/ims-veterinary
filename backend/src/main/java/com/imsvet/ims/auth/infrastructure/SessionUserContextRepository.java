package com.imsvet.ims.auth.infrastructure;

import com.imsvet.ims.auth.application.AuthSession;
import com.imsvet.ims.auth.application.SessionUser;
import com.imsvet.ims.users.application.AccountView;
import com.imsvet.ims.users.application.UserAccounts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.util.function.SingletonSupplier;

/**
 * ADR-036: the security context is rebuilt on every request from the user id stored in the session, so a
 * deactivated user or a changed role takes effect at once (BR-SEC-004). Sessions older than the absolute
 * timeout, or of a user that is gone or inactive, are invalidated. Nothing else is stored in the session.
 */
public class SessionUserContextRepository implements SecurityContextRepository {

    private final UserAccounts accounts;
    private final Clock clock;
    private final Duration absoluteTimeout;

    public SessionUserContextRepository(UserAccounts accounts, Clock clock, Duration absoluteTimeout) {
        this.accounts = accounts;
        this.clock = clock;
        this.absoluteTimeout = absoluteTimeout;
    }

    @Override
    public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
        Supplier<Optional<SecurityContext>> loaded = SingletonSupplier.of(() -> load(request));
        return new DeferredSecurityContext() {
            @Override
            public SecurityContext get() {
                return loaded.get().orElseGet(SecurityContextHolder::createEmptyContext);
            }

            @Override
            public boolean isGenerated() {
                return loaded.get().isEmpty();
            }
        };
    }

    @Override
    @Deprecated
    public SecurityContext loadContext(HttpRequestResponseHolder holder) {
        return loadDeferredContext(holder.getRequest()).get();
    }

    /** The session is written only at login ({@code AuthSessions}); the context itself is never stored. */
    @Override
    public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
    }

    @Override
    public boolean containsContext(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(AuthSession.USER_ID) != null;
    }

    private Optional<SecurityContext> load(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(AuthSession.USER_ID) instanceof Long userId)) {
            return Optional.empty();
        }
        Instant authenticatedAt = (Instant) session.getAttribute(AuthSession.AUTHENTICATED_AT);
        Optional<AccountView> account = authenticatedAt == null
                || clock.instant().isAfter(authenticatedAt.plus(absoluteTimeout))
                ? Optional.empty()
                : accounts.activeAccount(userId);
        if (account.isEmpty()) {
            session.invalidate();
            return Optional.empty();
        }
        List<SimpleGrantedAuthority> authorities = account.get().permissions().stream()
                .map(SimpleGrantedAuthority::new).toList();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new SessionUser(account.get()), null, authorities));
        return Optional.of(context);
    }
}
