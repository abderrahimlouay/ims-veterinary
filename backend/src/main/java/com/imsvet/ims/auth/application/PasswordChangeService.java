package com.imsvet.ims.auth.application;

import com.imsvet.ims.common.security.SecurityLog;
import com.imsvet.ims.users.application.UserAccounts;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A user changes their own password (ADR-039); also ends a forced change after a temporary password.
 */
@Service
public class PasswordChangeService {

    private final UserAccounts accounts;

    public PasswordChangeService(UserAccounts accounts) {
        this.accounts = accounts;
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional
    public void changeOwnPassword(String currentPassword, String newPassword) {
        SessionUser user = CurrentUserService.current();
        accounts.changeOwnPassword(user.userId(), currentPassword, newPassword);
        SecurityLog.LOG.atInfo().addKeyValue("event", "password_changed")
                .addKeyValue("username", user.account().username()).log("Security event password_changed");
    }
}
