package com.imsvet.ims.demo;

import com.imsvet.ims.common.actor.ActingUser;
import java.util.Collection;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** The administrator the demo history is recorded under, with every permission, for the seeder's thread only. */
record DemoActor(long userId) implements ActingUser {

    static void signIn(long userId, Collection<String> permissions) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new DemoActor(userId), null, permissions.stream().map(SimpleGrantedAuthority::new).toList()));
    }

    static void signOut() {
        SecurityContextHolder.clearContext();
    }
}
