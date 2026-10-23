package com.imsvet.ims.inventory.infrastructure;

import com.imsvet.ims.inventory.domain.ReorderRun;
import java.time.Instant;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Generations of reorder proposals; never deleted. */
public interface ReorderRunRepository extends Repository<ReorderRun, Long> {

    ReorderRun save(ReorderRun run);

    /** Whether the daily job already ran since {@code from} (ADR-078). */
    @Query("SELECT COUNT(r) > 0 FROM ReorderRun r WHERE r.triggeredBy IS NULL AND r.startedAt >= :from")
    boolean dailyRunSince(Instant from);
}
