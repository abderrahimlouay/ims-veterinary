package com.imsvet.ims.common.web;

import com.imsvet.ims.common.actor.ActingUser;
import com.imsvet.ims.common.actor.Actor;
import com.imsvet.ims.common.actor.ActorProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Actor of the current thread: the authenticated user (ADR-036) and the client IP of the HTTP request. The
 * client IP is the servlet remote address; in production the reverse proxy's {@code X-Forwarded-For} is
 * applied by Tomcat only for internal proxy addresses ({@code server.forward-headers-strategy: native},
 * security.md §11).
 */
@Component
public class RequestActorProvider implements ActorProvider {

    @Override
    public Actor current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Long userId = authentication != null && authentication.getPrincipal() instanceof ActingUser user
                ? user.userId()
                : null;
        String ip = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest().getRemoteAddr()
                : null;
        return new Actor(userId, ip);
    }
}
