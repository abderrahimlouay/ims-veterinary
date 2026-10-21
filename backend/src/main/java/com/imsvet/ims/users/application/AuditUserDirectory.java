package com.imsvet.ims.users.application;

import com.imsvet.ims.audit.application.UserDirectory;
import com.imsvet.ims.users.domain.User;
import com.imsvet.ims.users.infrastructure.UserRepository;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code users} side of ADR-046: names for the audit viewer, read in one query.
 */
@Component
class AuditUserDirectory implements UserDirectory {

    private final UserRepository users;

    AuditUserDirectory(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, UserName> namesOf(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return users.findAllByIdIn(userIds).stream()
                .collect(Collectors.toMap(User::id, u -> new UserName(u.username(), u.fullName())));
    }
}
