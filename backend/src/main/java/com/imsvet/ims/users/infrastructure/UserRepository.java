package com.imsvet.ims.users.infrastructure;

import com.imsvet.ims.users.domain.User;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * Users are deactivated, never deleted (database.md §1): no delete method.
 */
public interface UserRepository extends Repository<User, Long> {

    User save(User user);

    User saveAndFlush(User user);

    void flush();

    Optional<User> findById(Long id);

    List<User> findAllByIdIn(Collection<Long> ids);

    /** Case- and accent-insensitive through the column collation (ADR-041). */
    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    /** The user with role and permissions, for building the session principal on each request. */
    @Query("SELECT u FROM User u JOIN FETCH u.role r LEFT JOIN FETCH r.permissions WHERE u.id = :id")
    Optional<User> findWithPermissions(Long id);

    /** {@code pattern} is a {@code LIKE} pattern escaped with {@code !} (security.md §8), or {@code null}. */
    @Query(value = """
            SELECT u FROM User u JOIN FETCH u.role r
            WHERE (:pattern IS NULL OR u.username LIKE :pattern ESCAPE '!' OR u.fullName LIKE :pattern ESCAPE '!'
                   OR u.email LIKE :pattern ESCAPE '!')
              AND (:roleId IS NULL OR r.id = :roleId)
              AND (:active IS NULL OR u.active = :active)""",
            countQuery = """
            SELECT COUNT(u) FROM User u
            WHERE (:pattern IS NULL OR u.username LIKE :pattern ESCAPE '!' OR u.fullName LIKE :pattern ESCAPE '!'
                   OR u.email LIKE :pattern ESCAPE '!')
              AND (:roleId IS NULL OR u.role.id = :roleId)
              AND (:active IS NULL OR u.active = :active)""")
    Page<User> search(String pattern, Short roleId, Boolean active, Pageable pageable);

    /**
     * ADR-042: active users whose role holds both {@code user.manage} and {@code role.manage}.
     */
    @Query(value = """
            SELECT COUNT(*) FROM users u
            WHERE u.is_active AND u.role_id IN (
                SELECT rp.role_id FROM role_permissions rp JOIN permissions p ON p.id = rp.permission_id
                WHERE p.code IN ('user.manage', 'role.manage')
                GROUP BY rp.role_id HAVING COUNT(DISTINCT p.code) = 2)""", nativeQuery = true)
    long countActiveAdministrators();

    long count();

    /** Bypasses {@code version}: a login must not conflict with an administrator's edit (ADR-027). */
    @Modifying
    @Query("UPDATE User u SET u.lastLoginAt = :at WHERE u.id = :id")
    void recordLogin(Long id, Instant at);
}
