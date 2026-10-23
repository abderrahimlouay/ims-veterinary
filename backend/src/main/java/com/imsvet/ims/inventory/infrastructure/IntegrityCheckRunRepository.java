package com.imsvet.ims.inventory.infrastructure;

import com.imsvet.ims.inventory.domain.IntegrityCheckRun;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Integrity-check runs (ADR-086) and the ledger comparison they make. */
public interface IntegrityCheckRunRepository extends Repository<IntegrityCheckRun, Long> {

    IntegrityCheckRun saveAndFlush(IntegrityCheckRun run);

    Optional<IntegrityCheckRun> findFirstByOrderByStartedAtDescIdDesc();

    /** Whether the nightly job (no user) already started a run since {@code since}. */
    @Query("SELECT COUNT(r) > 0 FROM IntegrityCheckRun r WHERE r.triggeredBy IS NULL AND r.startedAt >= :since")
    boolean nightlyRunSince(Instant since);

    @Query(nativeQuery = true, value = "SELECT COUNT(*) FROM lots")
    long countLots();

    /** Lots whose balance differs from the sum of their movements: id, lot number, product id, balance, ledger sum. */
    @Query(nativeQuery = true, value = """
            SELECT l.id, l.lot_number, l.product_id, l.quantity_on_hand, COALESCE(SUM(m.quantity), 0)
            FROM lots l LEFT JOIN stock_movements m ON m.lot_id = l.id
            GROUP BY l.id, l.lot_number, l.product_id, l.quantity_on_hand
            HAVING l.quantity_on_hand <> COALESCE(SUM(m.quantity), 0)
            ORDER BY l.id""")
    List<Object[]> ledgerMismatches();
}
