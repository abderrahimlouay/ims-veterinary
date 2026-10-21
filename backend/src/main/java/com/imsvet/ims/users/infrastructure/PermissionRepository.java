package com.imsvet.ims.users.infrastructure;

import com.imsvet.ims.users.domain.Permission;
import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.Repository;

public interface PermissionRepository extends Repository<Permission, Integer> {

    List<Permission> findAllByOrderByCodeAsc();

    List<Permission> findByCodeIn(Collection<String> codes);
}
