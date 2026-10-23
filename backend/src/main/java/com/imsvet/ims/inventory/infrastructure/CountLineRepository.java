package com.imsvet.ims.inventory.infrastructure;

import com.imsvet.ims.inventory.domain.CountLine;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Count lines; never deleted (a count is closed or cancelled, never removed). */
public interface CountLineRepository extends Repository<CountLine, Long> {

    CountLine saveAndFlush(CountLine line);

    <S extends CountLine> List<S> saveAll(Iterable<S> lines);

    Optional<CountLine> findByIdAndCountId(long id, long countId);

    List<CountLine> findByCountIdOrderById(long countId);

    boolean existsByCountIdAndLotId(long countId, long lotId);

    /** Lot numbers compare ignoring case and accents, like lots (ADR-058). */
    boolean existsByCountIdAndProductIdAndFoundLotNumber(long countId, long productId, String foundLotNumber);

    /** Rows: count id, lines, counted lines. */
    @Query("""
            SELECT l.countId, COUNT(l), SUM(CASE WHEN l.countedQty IS NOT NULL THEN 1 ELSE 0 END)
            FROM CountLine l WHERE l.countId IN :countIds GROUP BY l.countId""")
    List<Object[]> tallies(Collection<Long> countIds);

    /**
     * Signed sum of each counted lot line's movements after its watermark (ADR-072). Rows: line id, sum; lines
     * without such movements are absent.
     */
    @Query("""
            SELECT l.id, SUM(m.quantity) FROM CountLine l, StockMovement m
            WHERE l.countId = :countId AND l.countedQty IS NOT NULL AND m.lotId = l.lotId
              AND m.id > COALESCE(l.watermarkMovementId, 0)
            GROUP BY l.id""")
    List<Object[]> movementsSinceCount(long countId);
}
