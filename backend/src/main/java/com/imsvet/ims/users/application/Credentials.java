package com.imsvet.ims.users.application;

/**
 * What login needs to verify a password; stays inside the services (never serialised, never logged).
 */
public record Credentials(long userId, String passwordHash, boolean active) {

    @Override
    public String toString() {
        return "Credentials[userId=" + userId + ", active=" + active + "]";
    }
}
