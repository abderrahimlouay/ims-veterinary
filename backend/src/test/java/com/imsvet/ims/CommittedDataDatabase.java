package com.imsvet.ims;

import org.springframework.boot.test.context.TestConfiguration;

/**
 * Imported next to {@link TestcontainersConfiguration} by tests that must commit rows of the append-only ledgers
 * (concurrency, triggers): such rows can never be deleted, so these tests get their own Spring context and therefore
 * their own MySQL container (ADR-035), and the shared database stays as the other tests expect it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CommittedDataDatabase {
}
