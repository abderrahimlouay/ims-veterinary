package com.imsvet.ims.users.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.security.SecurityLog;
import com.imsvet.ims.users.application.UserViews.PermissionSummary;
import com.imsvet.ims.users.application.UserViews.RoleDetail;
import com.imsvet.ims.users.domain.Permission;
import com.imsvet.ims.users.domain.Role;
import com.imsvet.ims.users.infrastructure.PermissionRepository;
import com.imsvet.ims.users.infrastructure.RoleRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roles and the permission catalogue (ADR-037). Only the role-permission mapping changes at runtime;
 * every change is audited and checked against administrator lock-out (ADR-042).
 */
@Service
public class RoleAdministration {

    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final AdministrationGuard guard;
    private final AuditRecorder audit;

    public RoleAdministration(RoleRepository roles, PermissionRepository permissions, AdministrationGuard guard,
            AuditRecorder audit) {
        this.roles = roles;
        this.permissions = permissions;
        this.guard = guard;
        this.audit = audit;
    }

    @PreAuthorize("hasAuthority('user.read')")
    @Transactional(readOnly = true)
    public List<RoleDetail> roles() {
        return roles.findAllWithPermissions().stream().map(UserViews::role).toList();
    }

    @PreAuthorize("hasAuthority('user.read')")
    @Transactional(readOnly = true)
    public RoleDetail role(short id) {
        return UserViews.role(find(id));
    }

    @PreAuthorize("hasAuthority('user.read')")
    @Transactional(readOnly = true)
    public List<PermissionSummary> permissions() {
        return permissions.findAllByOrderByCodeAsc().stream().map(UserViews::permission).toList();
    }

    @PreAuthorize("hasAuthority('role.manage')")
    @Transactional
    public RoleDetail replacePermissions(short roleId, Set<String> codes) {
        guard.lock();
        Role role = find(roleId);
        List<Permission> found = permissions.findByCodeIn(codes);
        if (found.size() != codes.size()) {
            Set<String> unknown = new TreeSet<>(codes);
            found.forEach(p -> unknown.remove(p.code()));
            throw new ValidationException("Unknown permission codes.",
                    List.of(new Violation("permissionCodes", "UNKNOWN", "Unknown: " + String.join(", ", unknown))));
        }
        Set<String> before = codes(role.permissions());
        if (!before.equals(new TreeSet<>(codes))) {
            role.replacePermissions(new HashSet<>(found));
            guard.verify();
            audit.record(AuditAction.ROLE_PERMISSION_CHANGE, "roles", (long) role.id(),
                    Map.of("permissions", before), Map.of("permissions", new TreeSet<>(codes)));
            SecurityLog.LOG.atInfo().addKeyValue("event", "role_permissions_changed").addKeyValue("role", role.name())
                    .log("Security event role_permissions_changed");
        }
        return UserViews.role(role);
    }

    private Role find(short id) {
        return roles.findById(id).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Role not found.", Map.of("id", id)));
    }

    private static Set<String> codes(Set<Permission> permissions) {
        return permissions.stream().map(Permission::code).collect(Collectors.toCollection(TreeSet::new));
    }
}
