package com.imsvet.ims.inventory.application;

import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.inventory.domain.IntegrityCheckRun;
import com.imsvet.ims.inventory.infrastructure.IntegrityCheckRunRepository;
import com.imsvet.ims.users.application.UserNames;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * api.md §4.5 integrity check (ADR-086): the latest run (it drives the banner shown to {@code audit.read} holders
 * until a clean run) and a run on demand.
 */
@Service
public class IntegrityChecks {

    public record Mismatch(long lotId, String lotNumber, long productId, BigDecimal balance, BigDecimal ledgerSum) {
    }

    public record RunUser(long id, String fullName) {
    }

    /** api.md §4.5 {@code IntegrityRun}; {@code triggeredBy} {@code null} for the nightly job. */
    public record IntegrityRun(long id, Instant startedAt, Instant finishedAt, int lotsChecked, int mismatchCount,
            List<Mismatch> mismatches, RunUser triggeredBy) {
    }

    private final IntegrityCheckRunner runner;
    private final IntegrityCheckRunRepository runs;
    private final ActorProvider actors;
    private final UserNames userNames;
    private final JsonMapper json;

    public IntegrityChecks(IntegrityCheckRunner runner, IntegrityCheckRunRepository runs, ActorProvider actors,
            UserNames userNames, JsonMapper json) {
        this.runner = runner;
        this.runs = runs;
        this.actors = actors;
        this.userNames = userNames;
        this.json = json;
    }

    @PreAuthorize("hasAuthority('audit.read')")
    @Transactional(readOnly = true)
    public Optional<IntegrityRun> latest() {
        return runs.findFirstByOrderByStartedAtDescIdDesc().map(this::view);
    }

    @PreAuthorize("hasAuthority('audit.read')")
    @Transactional
    public IntegrityRun runNow() {
        return view(runner.run(actors.current().userId()));
    }

    private IntegrityRun view(IntegrityCheckRun run) {
        List<Mismatch> mismatches = run.mismatches() == null ? List.of()
                : json.readValue(run.mismatches(), new TypeReference<List<Mismatch>>() { });
        RunUser by = null;
        if (run.triggeredBy() != null) {
            Map<Long, String> names = userNames.fullNames(List.of(run.triggeredBy()));
            by = new RunUser(run.triggeredBy(), names.get(run.triggeredBy()));
        }
        return new IntegrityRun(run.id(), run.startedAt(), run.finishedAt(), run.lotsChecked(), run.mismatchCount(),
                mismatches, by);
    }
}
