package com.imsvet.ims.audit.application;

import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.audit.domain.AuditEntry;
import com.imsvet.ims.audit.infrastructure.AuditEntryRepository;
import com.imsvet.ims.common.actor.Actor;
import com.imsvet.ims.common.actor.ActorProvider;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only way to write the audit log (backend.md §3). Must run inside the caller's transaction, so the
 * entry commits with the audited action or not at all (BR-AUDIT-003); outside a transaction it fails.
 */
@Service
public class AuditRecorder {

    /** security.md §7: passwords, tokens and hashes never reach audit values. */
    private static final Set<String> FORBIDDEN_KEY_PARTS = Set.of("password", "token", "hash", "secret");

    private final AuditEntryRepository repository;
    private final ActorProvider actors;
    private final Clock clock;
    private final JsonMapper json;

    public AuditRecorder(AuditEntryRepository repository, ActorProvider actors, Clock clock, JsonMapper json) {
        this.repository = repository;
        this.actors = actors;
        this.clock = clock;
        this.json = json;
    }

    /**
     * Records one audited action. {@code oldValues}/{@code newValues} hold only the business fields that
     * matter for the action (may be {@code null}); decimals are written as strings (ADR-021).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditAction action, String entity, Long entityId, Map<String, ?> oldValues,
            Map<String, ?> newValues) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(entity, "entity");
        rejectSensitiveKeys(oldValues);
        rejectSensitiveKeys(newValues);
        Actor actor = actors.current();
        repository.save(new AuditEntry(actor.userId(), action, entity, entityId, toJson(oldValues),
                toJson(newValues), actor.ipAddress(), clock.instant()));
    }

    private String toJson(Map<String, ?> values) {
        return values == null ? null : json.writeValueAsString(values);
    }

    private static void rejectSensitiveKeys(Map<?, ?> values) {
        if (values == null) {
            return;
        }
        for (Map.Entry<?, ?> e : values.entrySet()) {
            String key = String.valueOf(e.getKey()).toLowerCase(Locale.ROOT);
            if (FORBIDDEN_KEY_PARTS.stream().anyMatch(key::contains)) {
                throw new IllegalArgumentException("Audit values must not contain '" + e.getKey() + "'");
            }
            if (e.getValue() instanceof Map<?, ?> nested) {
                rejectSensitiveKeys(nested);
            }
        }
    }
}
