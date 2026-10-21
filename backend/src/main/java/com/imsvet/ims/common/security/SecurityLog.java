package com.imsvet.ims.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The dedicated security-event logger (ADR-031, security.md §12). Events carry an {@code event} key and
 * never a password, token or hash.
 */
public final class SecurityLog {

    public static final Logger LOG = LoggerFactory.getLogger("ims.security");

    private SecurityLog() {
    }
}
