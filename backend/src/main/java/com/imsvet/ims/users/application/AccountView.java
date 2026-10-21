package com.imsvet.ims.users.application;

import java.util.Set;

/**
 * An active user as seen by authentication: identity, role and permission codes. No password data.
 */
public record AccountView(long id, String username, String fullName, String email, String role,
        Set<String> permissions, boolean mustChangePassword) {
}
