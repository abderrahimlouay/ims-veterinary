package com.imsvet.ims.auth.api;

import com.imsvet.ims.users.application.AccountView;
import java.util.List;

/**
 * {@code CurrentUser} of api.md §4.1, returned by login and {@code me}. Never contains password data.
 */
public record CurrentUserResponse(UserSummary user, List<String> permissions, boolean mustChangePassword) {

    public record UserSummary(long id, String username, String fullName, String email, String role) {
    }

    static CurrentUserResponse of(AccountView a) {
        return new CurrentUserResponse(new UserSummary(a.id(), a.username(), a.fullName(), a.email(), a.role()),
                a.permissions().stream().sorted().toList(), a.mustChangePassword());
    }
}
