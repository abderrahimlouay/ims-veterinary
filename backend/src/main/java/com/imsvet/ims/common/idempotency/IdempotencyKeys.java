package com.imsvet.ims.common.idempotency;

import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.security.InternalService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code Idempotency-Key} for {@code POST}s that create numbered documents (ADR-104, BR-PURCH-013, BR-SALE-016).
 * Runs only in the caller's transaction, first of all its locks (database.md §7): the claim is stored before the
 * document exists, so a parallel request with the same key waits until the first commits and then replays it; a
 * rollback removes the claim with the document. The same key and request returns the first document's id; another
 * request is {@code IDEMPOTENCY_CONFLICT}. The request is hashed (SHA-256) as the JSON of the request object, so
 * formatting and field order of the body do not matter. Without a key, nothing is stored.
 */
@Service
@InternalService
public class IdempotencyKeys {

    public static final String HEADER = "Idempotency-Key";

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** The outcome of a claim: proceed and {@link #complete}, or return the {@link #replayed} document. */
    public static final class Claim {

        private static final Claim WITHOUT_KEY = new Claim(null, null);

        private final IdempotencyKey key;
        private final Long replayed;

        private Claim(IdempotencyKey key, Long replayed) {
            this.key = key;
            this.replayed = replayed;
        }

        /** The id of the document created by the first request with this key, if this is a repeat. */
        public Optional<Long> replayed() {
            return Optional.ofNullable(replayed);
        }

        /** Records the document just created; called in the same transaction, unless {@link #replayed}. */
        public void complete(long resourceId) {
            if (replayed != null) {
                throw new IllegalStateException("A replayed claim names an existing document");
            }
            if (key != null) {
                key.complete(resourceId);
            }
        }
    }

    private final IdempotencyKeyRepository keys;
    private final JsonMapper json;
    private final Clock clock;

    public IdempotencyKeys(IdempotencyKeyRepository keys, JsonMapper json, Clock clock) {
        this.keys = keys;
        this.json = json;
        this.clock = clock;
    }

    /** {@code key} is the header value, {@code null} when absent; {@code request} the parsed request body. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Claim claim(long userId, String endpoint, String key, Object request, String resourceType) {
        if (key == null) {
            return Claim.WITHOUT_KEY;
        }
        if (!UUID.matcher(key).matches()) {
            throw new ValidationException("The idempotency key must be a UUID.",
                    List.of(new Violation(HEADER, "PATTERN", "must be a UUID")));
        }
        String normalised = key.toLowerCase();
        String hash = hash(request);
        keys.claimIfAbsent(userId, endpoint, normalised, hash, resourceType, clock.instant());
        IdempotencyKey claimed = keys.findForUpdate(userId, endpoint, normalised).orElseThrow();
        if (!claimed.requestHash().equals(hash)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "This idempotency key was already used for another request.", Map.of("key", normalised));
        }
        return new Claim(claimed, claimed.resourceId());
    }

    private String hash(Object request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(request).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
