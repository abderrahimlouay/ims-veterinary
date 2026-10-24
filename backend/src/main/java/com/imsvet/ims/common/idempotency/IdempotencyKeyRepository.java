package com.imsvet.ims.common.idempotency;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Keys are inserted by a claim and completed once; never deleted. */
interface IdempotencyKeyRepository extends Repository<IdempotencyKey, Long> {

    /**
     * Inserts the claim unless the key exists. A claim of the same key still uncommitted elsewhere makes this wait on
     * the unique key until that transaction ends; then it inserts (rolled back) or does nothing (committed).
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO idempotency_keys (user_id, endpoint, idem_key, request_hash, resource_type, created_at)
            VALUES (:userId, :endpoint, :key, :requestHash, :resourceType, :createdAt)
            ON DUPLICATE KEY UPDATE id = id""")
    int claimIfAbsent(long userId, String endpoint, String key, String requestHash, String resourceType,
            Instant createdAt);

    /** {@code FOR UPDATE}: the key stays locked until the caller's transaction ends. */
    @Query(nativeQuery = true, value = """
            SELECT * FROM idempotency_keys WHERE user_id = :userId AND endpoint = :endpoint AND idem_key = :key
            FOR UPDATE""")
    Optional<IdempotencyKey> findForUpdate(long userId, String endpoint, String key);

}
