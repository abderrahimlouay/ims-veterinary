package com.imsvet.ims.common.web;

import com.imsvet.ims.common.actor.Actor;
import com.imsvet.ims.common.actor.ActorProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Actor of the current HTTP request. The client IP is the servlet remote address; in production the
 * reverse proxy's {@code X-Forwarded-For} is applied by Tomcat only for internal proxy addresses
 * ({@code server.forward-headers-strategy: native}, security.md §11). The user id is resolved from the
 * session once the security chain exists (ADR-036, Phase 1 login slice); until then it is {@code null}.
 */
@Component
public class RequestActorProvider implements ActorProvider {

    @Override
    public Actor current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return new Actor(null, attributes.getRequest().getRemoteAddr());
        }
        return Actor.SYSTEM;
    }
}
