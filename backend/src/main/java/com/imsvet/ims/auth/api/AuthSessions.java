package com.imsvet.ims.auth.api;

import com.imsvet.ims.auth.application.AuthSession;
import com.imsvet.ims.common.security.SecurityLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.FindByIndexNameSessionRepository;
import com.imsvet.ims.users.application.UserSessions;
import org.springframework.stereotype.Component;

/**
 * The HTTP side of ADR-036: a new session id at every login and password change (fixation protection),
 * only the user id and login instant stored in the session, invalidation at logout; a password change also ends
 * the user's other sessions (ADR-154).
 */
@Component
public class AuthSessions {

    private final Clock clock;
    private final UserSessions userSessions;

    public AuthSessions(Clock clock, UserSessions userSessions) {
        this.clock = clock;
        this.userSessions = userSessions;
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

    /**
     * After the user changed their own password (ADR-154): every other session of the user is deleted, then the
     * current one gets a new id. The others are found before the rotation, by the id the store still holds.
     */
    void rotateAndEndOthers(HttpServletRequest request, long userId) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        userSessions.endOthersAfterCommit(userId, session.getId());
        request.changeSessionId();
        SecurityLog.LOG.atInfo().addKeyValue("event", "other_sessions_ended").addKeyValue("userId", userId)
                .log("Security event other_sessions_ended");
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
