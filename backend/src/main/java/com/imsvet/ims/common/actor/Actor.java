package com.imsvet.ims.common.actor;

/**
 * Who performs the current action and from where, as recorded in the audit log (BR-AUDIT-002).
 * Both parts are {@code null} for system actions outside an HTTP request.
 */
public record Actor(Long userId, String ipAddress) {

    public static final Actor SYSTEM = new Actor(null, null);
}
