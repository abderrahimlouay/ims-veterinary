package com.imsvet.ims.audit.application;

import java.util.Collection;
import java.util.Map;

/**
 * Names of the users who acted, for the audit viewer (ADR-046). Defined here and implemented by
 * {@code users}, because {@code audit} may not depend on {@code users} (architecture.md §3: no cycles).
 */
public interface UserDirectory {

    record UserName(String username, String fullName) {
    }

    /** One query for all ids; unknown ids are absent from the result. */
    Map<Long, UserName> namesOf(Collection<Long> userIds);
}
