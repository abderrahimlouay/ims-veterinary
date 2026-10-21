package com.imsvet.ims.users.api;

import com.imsvet.ims.users.application.RoleAdministration;
import com.imsvet.ims.users.application.UserViews.PermissionSummary;
import com.imsvet.ims.users.application.UserViews.RoleDetail;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * api.md §4.2 roles and permissions; permissions on the service ({@code user.read}, {@code role.manage}).
 */
@RestController
class RoleController {

    record ReplacePermissionsRequest(@NotNull Set<String> permissionCodes) {
    }

    private final RoleAdministration roles;

    RoleController(RoleAdministration roles) {
        this.roles = roles;
    }

    @GetMapping("/api/roles")
    List<RoleDetail> roles() {
        return roles.roles();
    }

    @GetMapping("/api/roles/{id}")
    RoleDetail role(@PathVariable short id) {
        return roles.role(id);
    }

    @GetMapping("/api/permissions")
    List<PermissionSummary> permissions() {
        return roles.permissions();
    }

    @PutMapping("/api/roles/{id}/permissions")
    RoleDetail replacePermissions(@PathVariable short id, @Valid @RequestBody ReplacePermissionsRequest body) {
        return roles.replacePermissions(id, body.permissionCodes());
    }
}
