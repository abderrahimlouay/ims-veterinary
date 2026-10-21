package com.imsvet.ims.users.application;

import com.imsvet.ims.users.domain.Permission;
import com.imsvet.ims.users.domain.Role;
import com.imsvet.ims.users.domain.User;
import java.time.Instant;
import java.util.List;

/**
 * Read models of user and role administration (api.md §4.2). No password data, ever.
 */
public final class UserViews {

    private UserViews() {
    }

    public record RoleRef(short id, String name) {
    }

    public record UserSummary(long id, String username, String fullName, String email, RoleRef role,
            boolean active, boolean mustChangePassword, Instant lastLoginAt) {
    }

    public record UserDetail(long id, String username, String fullName, String email, RoleRef role,
            boolean active, boolean mustChangePassword, Instant lastLoginAt, Instant createdAt, long version) {
    }

    public record RoleDetail(short id, String name, String description, List<String> permissions) {
    }

    public record PermissionSummary(String code, String description) {
    }

    /** Shown once to the Administrator; never stored in clear, logged or audited (ADR-045). */
    public record TemporaryPassword(String temporaryPassword) {

        @Override
        public String toString() {
            return "TemporaryPassword[hidden]";
        }
    }

    static UserSummary summary(User u) {
        return new UserSummary(u.id(), u.username(), u.fullName(), u.email(), roleRef(u.role()), u.active(),
                u.mustChangePassword(), u.lastLoginAt());
    }

    static UserDetail detail(User u) {
        return new UserDetail(u.id(), u.username(), u.fullName(), u.email(), roleRef(u.role()), u.active(),
                u.mustChangePassword(), u.lastLoginAt(), u.createdAt(), u.version());
    }

    static RoleDetail role(Role r) {
        return new RoleDetail(r.id(), r.name(), r.description(),
                r.permissions().stream().map(Permission::code).sorted().toList());
    }

    static PermissionSummary permission(Permission p) {
        return new PermissionSummary(p.code(), p.description());
    }

    private static RoleRef roleRef(Role r) {
        return new RoleRef(r.id(), r.name());
    }
}
