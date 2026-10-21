package com.imsvet.ims.users.api;

import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.users.application.UserAdministration;
import com.imsvet.ims.users.application.UserAdministration.NewUser;
import com.imsvet.ims.users.application.UserAdministration.UserChanges;
import com.imsvet.ims.users.application.UserViews.TemporaryPassword;
import com.imsvet.ims.users.application.UserViews.UserDetail;
import com.imsvet.ims.users.application.UserViews.UserSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * api.md §4.2 users; permissions on the service ({@code user.read}, {@code user.manage}).
 */
@RestController
@RequestMapping("/api/users")
class UserController {

    record CreateUserRequest(@NotBlank @Size(max = 60) String username, @NotBlank @Size(max = 150) String fullName,
            @Email @Size(max = 150) String email, @NotNull Short roleId,
            @NotBlank @Size(max = 200) String initialPassword) {

        @Override
        public String toString() {
            return "CreateUserRequest[username=" + username + "]";
        }
    }

    /** The username is immutable (ADR-045); {@code version} as last read (ADR-027). */
    record UpdateUserRequest(@NotBlank @Size(max = 150) String fullName, @Email @Size(max = 150) String email,
            @NotNull Short roleId, @NotNull Long version) {
    }

    private final UserAdministration users;

    UserController(UserAdministration users) {
        this.users = users;
    }

    @GetMapping
    PageResponse<UserSummary> search(@RequestParam(required = false) String q,
            @RequestParam(required = false) Short roleId, @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return users.search(q, roleId, active, page, size);
    }

    @PostMapping
    ResponseEntity<UserDetail> create(@Valid @RequestBody CreateUserRequest body) {
        UserDetail created = users.create(new NewUser(body.username(), body.fullName(), body.email(), body.roleId(),
                body.initialPassword()));
        return ResponseEntity.created(URI.create("/api/users/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    UserDetail get(@PathVariable long id) {
        return users.get(id);
    }

    @PutMapping("/{id}")
    UserDetail update(@PathVariable long id, @Valid @RequestBody UpdateUserRequest body) {
        return users.update(id, new UserChanges(body.fullName(), body.email(), body.roleId(), body.version()));
    }

    @PostMapping("/{id}/deactivate")
    UserDetail deactivate(@PathVariable long id) {
        return users.deactivate(id);
    }

    @PostMapping("/{id}/activate")
    UserDetail activate(@PathVariable long id) {
        return users.activate(id);
    }

    @PostMapping("/{id}/reset-password")
    TemporaryPassword resetPassword(@PathVariable long id) {
        return users.resetPassword(id);
    }
}
