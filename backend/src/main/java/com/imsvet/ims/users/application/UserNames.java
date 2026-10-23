package com.imsvet.ims.users.application;

import com.imsvet.ims.common.security.InternalService;
import com.imsvet.ims.users.domain.User;
import com.imsvet.ims.users.infrastructure.UserRepository;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read port of the {@code users} module for other modules (ADR-089): full names by user id, in one query, e.g. who
 * blocked or recalled a lot. Callers check their own permission; nothing else about users is exposed.
 */
@Service
@InternalService
public class UserNames {

    private final UserRepository users;

    public UserNames(UserRepository users) {
        this.users = users;
    }

    /** Unknown ids are simply absent from the map. */
    @Transactional(readOnly = true)
    public Map<Long, String> fullNames(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return users.findAllByIdIn(userIds).stream().collect(Collectors.toMap(User::id, User::fullName));
    }
}
