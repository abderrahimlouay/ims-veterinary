package com.imsvet.ims.common.actor;

/**
 * Implemented by the authenticated principal, so {@code common} can read the acting user's id without
 * depending on the {@code auth} module.
 */
public interface ActingUser {

    long userId();
}
