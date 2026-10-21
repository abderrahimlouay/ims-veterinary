package com.imsvet.ims.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an application service that is a port for other services, not a use case reachable from an API:
 * it carries no {@code @PreAuthorize}; its callers check the permission (BR-SEC-002, ArchitectureTest).
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface InternalService {
}
