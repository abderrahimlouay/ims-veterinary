package com.imsvet.ims.users.infrastructure;

import com.imsvet.ims.users.domain.Role;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface RoleRepository extends Repository<Role, Short> {

    Optional<Role> findByName(String name);

    Optional<Role> findById(Short id);

    @Query("SELECT DISTINCT r FROM Role r LEFT JOIN FETCH r.permissions ORDER BY r.id")
    List<Role> findAllWithPermissions();

    /**
     * ADR-042: every change that could remove the last administrator first locks all role rows in id order,
     * which serialises such changes (READ COMMITTED, ADR-013).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Role r ORDER BY r.id")
    List<Role> lockAllForAdministration();
}
