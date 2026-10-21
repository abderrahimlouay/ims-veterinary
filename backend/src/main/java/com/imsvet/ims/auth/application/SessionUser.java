package com.imsvet.ims.auth.application;

import com.imsvet.ims.common.actor.ActingUser;
import com.imsvet.ims.users.application.AccountView;

/**
 * The authenticated principal of a request, rebuilt from the database on each request (ADR-036).
 */
public record SessionUser(AccountView account) implements ActingUser {

    @Override
    public long userId() {
        return account.id();
    }
}
