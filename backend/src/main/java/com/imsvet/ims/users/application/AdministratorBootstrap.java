package com.imsvet.ims.users.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.common.security.SecurityLog;
import com.imsvet.ims.users.domain.PasswordPolicy;
import com.imsvet.ims.users.domain.Role;
import com.imsvet.ims.users.domain.User;
import com.imsvet.ims.users.infrastructure.RoleRepository;
import com.imsvet.ims.users.infrastructure.UserRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-040: creates the first Administrator when no user exists. The password must change at first login;
 * it is never logged. A password that violates the policy stops the start-up.
 */
@Component
public class AdministratorBootstrap {

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final AuditRecorder audit;
    private final Clock clock;

    public AdministratorBootstrap(UserRepository users, RoleRepository roles, PasswordEncoder encoder,
            PasswordPolicy policy, AuditRecorder audit, Clock clock) {
        this.users = users;
        this.roles = roles;
        this.encoder = encoder;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
    }

    /** @return {@code true} if the administrator was created; {@code false} if users already exist. */
    @Transactional
    public boolean createIfNoUsers(String username, String password, String fullName) {
        if (users.count() > 0) {
            SecurityLog.LOG.atInfo().addKeyValue("event", "bootstrap_skipped")
                    .log("Bootstrap administrator ignored: users already exist");
            return false;
        }
        List<String> violations = policy.violations(password);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("IMS_BOOTSTRAP_ADMIN_PASSWORD violates the password policy: "
                    + violations);
        }
        Role administrator = roles.findByName(Role.ADMINISTRATOR).orElseThrow();
        User user = users.save(new User(username, encoder.encode(password), fullName, null, administrator, true,
                clock.instant()));
        audit.record(AuditAction.USER_CREATE, "users", user.id(), null,
                Map.of("username", username, "role", Role.ADMINISTRATOR, "bootstrap", true));
        SecurityLog.LOG.atWarn().addKeyValue("event", "bootstrap_admin_created").addKeyValue("username", username)
                .log("Bootstrap administrator created; remove IMS_BOOTSTRAP_ADMIN_* from the environment");
        return true;
    }
}
