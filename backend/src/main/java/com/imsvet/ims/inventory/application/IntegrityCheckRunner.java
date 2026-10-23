package com.imsvet.ims.inventory.application;

import com.imsvet.ims.common.security.SecurityLog;
import com.imsvet.ims.inventory.domain.IntegrityCheckRun;
import com.imsvet.ims.inventory.infrastructure.IntegrityCheckRunRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The integrity check itself (BR-STOCK-007, ADR-086): compares every lot balance with the sum of its movements,
 * stores the run and, on any mismatch, logs a CRITICAL {@code LEDGER_MISMATCH} security event. It reports only and
 * never corrects. Called by {@link IntegrityChecks} (on demand) and {@link IntegrityCheckJob} (nightly).
 */
@Component
public class IntegrityCheckRunner {

    private static final int QUANTITY_SCALE = 3;

    private final IntegrityCheckRunRepository runs;
    private final Clock clock;
    private final JsonMapper json;

    public IntegrityCheckRunner(IntegrityCheckRunRepository runs, Clock clock, JsonMapper json) {
        this.runs = runs;
        this.clock = clock;
        this.json = json;
    }

    /** {@code triggeredBy} {@code null}: the nightly job. */
    @Transactional
    public IntegrityCheckRun run(Long triggeredBy) {
        IntegrityCheckRun run = new IntegrityCheckRun(clock.instant(), triggeredBy);
        List<Map<String, Object>> mismatches = runs.ledgerMismatches().stream().map(IntegrityCheckRunner::mismatch)
                .toList();
        run.finish(clock.instant(), (int) runs.countLots(), mismatches.size(), json.writeValueAsString(mismatches));
        IntegrityCheckRun saved = runs.saveAndFlush(run);
        if (!mismatches.isEmpty()) {
            SecurityLog.LOG.atError().addKeyValue("event", "LEDGER_MISMATCH").addKeyValue("severity", "CRITICAL")
                    .addKeyValue("runId", saved.id()).addKeyValue("mismatchCount", mismatches.size())
                    .log("Security event LEDGER_MISMATCH (CRITICAL): {} lot(s) differ from their ledger",
                            mismatches.size());
        }
        return saved;
    }

    private static Map<String, Object> mismatch(Object[] row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lotId", ((Number) row[0]).longValue());
        m.put("lotNumber", row[1]);
        m.put("productId", ((Number) row[2]).longValue());
        m.put("balance", quantity(row[3]));
        m.put("ledgerSum", quantity(row[4]));
        return m;
    }

    private static String quantity(Object value) {
        return new BigDecimal(value.toString()).setScale(QUANTITY_SCALE).toPlainString();
    }
}
