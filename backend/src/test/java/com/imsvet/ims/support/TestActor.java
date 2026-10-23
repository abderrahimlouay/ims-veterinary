package com.imsvet.ims.support;

import com.imsvet.ims.common.actor.ActingUser;
import java.util.Arrays;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** An authenticated operator for service-level tests: movements and audit rows need a user id (BR-STOCK-004). */
public record TestActor(long userId) implements ActingUser {

    public static void signIn(long userId, String... permissions) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new TestActor(userId), null,
                Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }

    public static void signOut() {
        SecurityContextHolder.clearContext();
    }
}
