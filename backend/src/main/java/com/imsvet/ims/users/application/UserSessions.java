package com.imsvet.ims.users.application;

/**
 * Ends a user's sessions; implemented by {@code auth} (ADR-036), which owns sessions, so {@code users} does
 * not depend on it. Runs after the surrounding transaction commits, so a refused change ends no session.
 */
public interface UserSessions {

    void endAllAfterCommit(long userId);
}
