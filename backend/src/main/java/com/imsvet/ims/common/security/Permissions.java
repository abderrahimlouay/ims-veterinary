package com.imsvet.ims.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * For checks that depend on the request content (e.g. a price change needs {@code price.edit}), where a
 * method-level {@code @PreAuthorize} cannot express the rule (BR-SEC-002).
 */
public final class Permissions {

    private Permissions() {
    }

    public static boolean granted(String code) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getAuthorities().stream().anyMatch(a -> code.equals(a.getAuthority()));
    }
}
