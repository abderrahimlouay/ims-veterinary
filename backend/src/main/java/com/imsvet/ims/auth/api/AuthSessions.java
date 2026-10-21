package com.imsvet.ims.auth.api;

import com.imsvet.ims.auth.application.AuthSession;
import com.imsvet.ims.common.security.SecurityLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Component;

/**
 * The HTTP side of ADR-036: a new session id at every login and password change (fixation protection),
 * only the user id and login instant stored in the session, invalidation at logout.
 */
@Component
public class AuthSessions {

    private final Clock clock;

    public AuthSessions(Clock clock) {
        this.clock = clock;
    }

    void establish(HttpServletRequest request, long userId) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            request.changeSessionId();
        }
        session = request.getSession(true);
        session.setAttribute(AuthSession.USER_ID, userId);
        session.setAttribute(AuthSession.AUTHENTICATED_AT, clock.instant());
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, String.valueOf(userId));
    }

    void rotate(HttpServletRequest request) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
    }

    void end(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object userId = session.getAttribute(AuthSession.USER_ID);
            session.invalidate();
            SecurityLog.LOG.atInfo().addKeyValue("event", "logout").addKeyValue("userId", userId)
                    .log("Security event logout");
        }
        SecurityContextHolder.clearContext();
    }
}
