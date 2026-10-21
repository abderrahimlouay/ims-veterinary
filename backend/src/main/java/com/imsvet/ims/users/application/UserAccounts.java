package com.imsvet.ims.users.application;

import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.security.InternalService;
import com.imsvet.ims.users.domain.PasswordPolicy;
import com.imsvet.ims.users.domain.Permission;
import com.imsvet.ims.users.domain.User;
import com.imsvet.ims.users.infrastructure.UserRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account port used by the {@code auth} module (backend.md §3): credentials for login, the active account
 * of a session, the user's own password change. Permission checks are done by the calling use cases.
 */
@Service
@InternalService
public class UserAccounts {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final Clock clock;

    public UserAccounts(UserRepository users, PasswordEncoder encoder, PasswordPolicy policy, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.policy = policy;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<Credentials> credentialsFor(String username) {
        return users.findByUsername(username).map(u -> new Credentials(u.id(), u.passwordHash(), u.active()));
    }

    /** Empty when the user does not exist or is inactive (BR-SEC-004). */
    @Transactional(readOnly = true)
    public Optional<AccountView> activeAccount(long userId) {
        return users.findWithPermissions(userId).filter(User::active).map(UserAccounts::toView);
    }

    @Transactional
    public void recordLogin(long userId) {
        users.recordLogin(userId, clock.instant());
    }

    /** ADR-039: requires the current password; the new one must satisfy the policy. */
    @Transactional
    public void changeOwnPassword(long userId, String currentPassword, String newPassword) {
        User user = users.findById(userId).orElseThrow();
        if (!encoder.matches(currentPassword, user.passwordHash())) {
            throw new ValidationException("The current password is not correct.",
                    List.of(new Violation("currentPassword", "INVALID", "The current password is not correct.")));
        }
        List<Violation> violations = policy.violations(newPassword).stream()
                .map(code -> new Violation("newPassword", code, describe(code)))
                .toList();
        if (!violations.isEmpty()) {
            throw new ValidationException("The new password does not meet the password policy.", violations);
        }
        user.changeOwnPassword(encoder.encode(newPassword));
    }

    private String describe(String violation) {
        return PasswordPolicy.TOO_SHORT.equals(violation)
                ? "Use at least " + policy.minLength() + " characters."
                : "This password is too common.";
    }

    private static AccountView toView(User u) {
        return new AccountView(u.id(), u.username(), u.fullName(), u.email(), u.role().name(),
                u.role().permissions().stream().map(Permission::code).collect(Collectors.toUnmodifiableSet()),
                u.mustChangePassword());
    }
}
