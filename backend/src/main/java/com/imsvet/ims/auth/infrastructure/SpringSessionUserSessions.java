package com.imsvet.ims.auth.infrastructure;

import com.imsvet.ims.users.application.UserSessions;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Deletes a user's sessions through the principal index written at login (ADR-036). Spring Session writes
 * in its own transactions, so the deletion waits for the caller's commit: a rolled-back change ends nothing.
 */
@Component
class SpringSessionUserSessions implements UserSessions {

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    SpringSessionUserSessions(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    @Override
    public void endAllAfterCommit(long userId) {
        afterCommit(() -> end(userId, null));
    }

    @Override
    public void endOthersAfterCommit(long userId, String keptSessionId) {
        afterCommit(() -> end(userId, keptSessionId));
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private void end(long userId, String keptSessionId) {
        sessions.findByPrincipalName(String.valueOf(userId)).keySet().stream()
                .filter(id -> !id.equals(keptSessionId)).forEach(sessions::deleteById);
    }
}
