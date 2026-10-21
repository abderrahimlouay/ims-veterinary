package com.imsvet.ims.auth.application;

import com.imsvet.ims.users.application.AccountView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * The authenticated user of the request, as rebuilt from the database for this request (ADR-036).
 */
@Service
public class CurrentUserService {

    @PreAuthorize("isAuthenticated()")
    public AccountView me() {
        return current().account();
    }

    static SessionUser current() {
        return (SessionUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
