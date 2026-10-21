package com.imsvet.ims.users.application;

import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.users.infrastructure.RoleRepository;
import com.imsvet.ims.users.infrastructure.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-042: administration can never be locked out. Callers {@link #lock()} before changing users or roles
 * and {@link #verify()} after the change; a change that leaves no active user holding both
 * {@code user.manage} and {@code role.manage} is refused and the whole transaction rolls back.
 */
@Component
class AdministrationGuard {

    private final RoleRepository roles;
    private final UserRepository users;

    AdministrationGuard(RoleRepository roles, UserRepository users) {
        this.roles = roles;
        this.users = users;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void lock() {
        roles.lockAllForAdministration();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void verify() {
        users.flush();
        if (users.countActiveAdministrators() == 0) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This change would leave no active user able to manage users and roles.");
        }
    }
}
