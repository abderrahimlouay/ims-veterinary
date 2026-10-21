package com.imsvet.ims.common.actor;

/**
 * The {@link Actor} of the current thread. Lives in {@code common} so that every module, including
 * {@code audit}, can use it without depending on {@code auth}.
 */
public interface ActorProvider {

    Actor current();
}
