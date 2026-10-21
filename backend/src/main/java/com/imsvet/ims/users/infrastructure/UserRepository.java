package com.imsvet.ims.users.infrastructure;

import com.imsvet.ims.users.domain.User;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * Users are deactivated, never deleted (database.md §1): no delete method.
 */
public interface UserRepository extends Repository<User, Long> {

    User save(User user);

    Optional<User> findById(Long id);

    /** Case- and accent-insensitive through the column collation (ADR-041). */
    Optional<User> findByUsername(String username);

    /** The user with role and permissions, for building the session principal on each request. */
    @Query("SELECT u FROM User u JOIN FETCH u.role r LEFT JOIN FETCH r.permissions WHERE u.id = :id")
    Optional<User> findWithPermissions(Long id);

    long count();

    /** Bypasses {@code version}: a login must not conflict with an administrator's edit (ADR-027). */
    @Modifying
    @Query("UPDATE User u SET u.lastLoginAt = :at WHERE u.id = :id")
    void recordLogin(Long id, Instant at);
}
