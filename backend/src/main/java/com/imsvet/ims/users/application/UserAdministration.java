package com.imsvet.ims.users.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.paging.PageRequests;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.common.security.SecurityLog;
import com.imsvet.ims.users.application.UserViews.TemporaryPassword;
import com.imsvet.ims.users.application.UserViews.UserDetail;
import com.imsvet.ims.users.application.UserViews.UserSummary;
import com.imsvet.ims.users.domain.PasswordPolicy;
import com.imsvet.ims.users.domain.Role;
import com.imsvet.ims.users.domain.TemporaryPasswords;
import com.imsvet.ims.users.domain.User;
import com.imsvet.ims.users.infrastructure.RoleRepository;
import com.imsvet.ims.users.infrastructure.UserRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User administration (FR-SEC-002, api.md §4.2, ADR-045): each change is audited in its transaction
 * (BR-AUDIT-001/003) and checked against administrator lock-out (ADR-042).
 */
@Service
public class UserAdministration {

    public record NewUser(String username, String fullName, String email, short roleId, String initialPassword) {

        @Override
        public String toString() {
            return "NewUser[username=" + username + "]";
        }
    }

    public record UserChanges(String fullName, String email, short roleId, long version) {
    }

    private static final Sort BY_USERNAME = Sort.by("username");

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final AdministrationGuard guard;
    private final UserSessions sessions;
    private final AuditRecorder audit;
    private final ActorProvider actors;
    private final Clock clock;

    public UserAdministration(UserRepository users, RoleRepository roles, PasswordEncoder encoder,
            PasswordPolicy policy, AdministrationGuard guard, UserSessions sessions, AuditRecorder audit,
            ActorProvider actors, Clock clock) {
        this.users = users;
        this.roles = roles;
        this.encoder = encoder;
        this.policy = policy;
        this.guard = guard;
        this.sessions = sessions;
        this.audit = audit;
        this.actors = actors;
        this.clock = clock;
    }

    /** {@code q} matches username, full name or e-mail (contains, case- and accent-insensitive). */
    @PreAuthorize("hasAuthority('user.read')")
    @Transactional(readOnly = true)
    public PageResponse<UserSummary> search(String q, Short roleId, Boolean active, int page, int size) {
        String pattern = q == null || q.isBlank() ? null : "%" + q.trim().replaceAll("([!%_])", "!$1") + "%";
        return PageResponse.of(users.search(pattern, roleId, active, PageRequests.of(page, size, BY_USERNAME)),
                UserViews::summary);
    }

    @PreAuthorize("hasAuthority('user.read')")
    @Transactional(readOnly = true)
    public UserDetail get(long id) {
        return UserViews.detail(find(id));
    }

    /** The initial password is known to the Administrator, so it must be changed at first login (ADR-045). */
    @PreAuthorize("hasAuthority('user.manage')")
    @Transactional
    public UserDetail create(NewUser request) {
        String username = request.username().trim();
        String email = blankToNull(request.email());
        Role role = role(request.roleId());
        List<Violation> violations = policy.violations(request.initialPassword()).stream()
                .map(code -> new Violation("initialPassword", code, "The password does not meet the policy."))
                .toList();
        if (!violations.isEmpty()) {
            throw new ValidationException("The initial password does not meet the password policy.", violations);
        }
        rejectDuplicates(username, email, null);
        User user;
        try {
            user = users.saveAndFlush(new User(username, encoder.encode(request.initialPassword()),
                    request.fullName().trim(), email, role, true, clock.instant()));
        } catch (DataIntegrityViolationException e) {
            throw duplicate();
        }
        audit.record(AuditAction.USER_CREATE, "users", user.id(), null, fields(user));
        return UserViews.detail(user);
    }

    @PreAuthorize("hasAuthority('user.manage')")
    @Transactional
    public UserDetail update(long id, UserChanges changes) {
        guard.lock();
        User user = find(id);
        if (user.version() != changes.version()) {
            throw new ObjectOptimisticLockingFailureException(User.class, id);
        }
        String email = blankToNull(changes.email());
        rejectDuplicates(user.username(), email, user.id());
        Map<String, Object> before = fields(user);
        user.update(changes.fullName().trim(), email, role(changes.roleId()));
        Map<String, Object> after = fields(user);
        if (!before.equals(after)) {
            guard.verify();
            Map<String, Object> oldValues = new LinkedHashMap<>();
            Map<String, Object> newValues = new LinkedHashMap<>();
            after.forEach((key, value) -> {
                if (!Objects.equals(before.get(key), value)) {
                    oldValues.put(key, before.get(key));
                    newValues.put(key, value);
                }
            });
            audit.record(AuditAction.USER_UPDATE, "users", user.id(), oldValues, newValues);
            if (oldValues.containsKey("role")) {
                logEvent("user_role_changed", user);
            }
        }
        users.flush();
        return UserViews.detail(user);
    }

    /** Self-deactivation is refused; the user's sessions end at commit (ADR-042, ADR-036). */
    @PreAuthorize("hasAuthority('user.manage')")
    @Transactional
    public UserDetail deactivate(long id) {
        guard.lock();
        User user = find(id);
        if (Objects.equals(actors.current().userId(), user.id())) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "You cannot deactivate your own account.");
        }
        if (user.active()) {
            user.deactivate();
            guard.verify();
            audit.record(AuditAction.USER_DEACTIVATE, "users", user.id(), Map.of("active", true),
                    Map.of("active", false));
            sessions.endAllAfterCommit(user.id());
            logEvent("user_deactivated", user);
        }
        return UserViews.detail(user);
    }

    @PreAuthorize("hasAuthority('user.manage')")
    @Transactional
    public UserDetail activate(long id) {
        User user = find(id);
        if (!user.active()) {
            user.activate();
            audit.record(AuditAction.USER_UPDATE, "users", user.id(), Map.of("active", false),
                    Map.of("active", true));
            logEvent("user_activated", user);
        }
        users.flush();
        return UserViews.detail(user);
    }

    /** ADR-045: a generated temporary password, returned once; the user's sessions end at commit. */
    @PreAuthorize("hasAuthority('user.manage')")
    @Transactional
    public TemporaryPassword resetPassword(long id) {
        User user = find(id);
        String temporary = TemporaryPasswords.generate();
        if (!policy.violations(temporary).isEmpty()) {
            throw new IllegalStateException("Generated temporary password violates the policy");
        }
        user.resetPassword(encoder.encode(temporary));
        audit.record(AuditAction.PASSWORD_RESET, "users", user.id(), null, Map.of("mustChangePassword", true));
        sessions.endAllAfterCommit(user.id());
        logEvent("password_reset", user);
        return new TemporaryPassword(temporary);
    }

    private User find(long id) {
        return users.findById(id).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "User not found.", Map.of("id", id)));
    }

    private Role role(short roleId) {
        return roles.findById(roleId).orElseThrow(() -> new ValidationException("Unknown role.",
                List.of(new Violation("roleId", "UNKNOWN", "This role does not exist."))));
    }

    private void rejectDuplicates(String username, String email, Long self) {
        boolean usernameTaken = users.findByUsername(username).filter(u -> !u.id().equals(self)).isPresent();
        boolean emailTaken = email != null
                && users.findByEmail(email).filter(u -> !u.id().equals(self)).isPresent();
        if (usernameTaken || emailTaken) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE,
                    usernameTaken ? "This username is already used." : "This e-mail address is already used.",
                    Map.of("field", usernameTaken ? "username" : "email"));
        }
    }

    private static BusinessException duplicate() {
        return new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "This username or e-mail address is already used.");
    }

    private static Map<String, Object> fields(User u) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("username", u.username());
        fields.put("fullName", u.fullName());
        fields.put("email", u.email());
        fields.put("role", u.role().name());
        return fields;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void logEvent(String event, User user) {
        SecurityLog.LOG.atInfo().addKeyValue("event", event).addKeyValue("targetUser", user.username())
                .log("Security event {}", event);
    }
}
