package com.imsvet.ims.auth.application;

/**
 * Session attribute names (ADR-036): the session holds the user id and the login instant only; role and
 * permissions are re-read on every request.
 */
public final class AuthSession {

    public static final String USER_ID = "ims.auth.userId";
    public static final String AUTHENTICATED_AT = "ims.auth.authenticatedAt";

    private AuthSession() {
    }
}
