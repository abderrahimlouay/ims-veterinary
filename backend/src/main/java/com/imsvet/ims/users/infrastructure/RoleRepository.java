package com.imsvet.ims.users.infrastructure;

import com.imsvet.ims.users.domain.Role;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface RoleRepository extends Repository<Role, Short> {

    Optional<Role> findByName(String name);
}
