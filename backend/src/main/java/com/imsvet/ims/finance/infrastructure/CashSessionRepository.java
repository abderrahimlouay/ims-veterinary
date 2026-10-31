package com.imsvet.ims.finance.infrastructure;

import com.imsvet.ims.finance.domain.CashSession;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface CashSessionRepository extends Repository<CashSession, Long> {

    CashSession saveAndFlush(CashSession session);

    Optional<CashSession> findById(Long id);

    @Query("SELECT s FROM CashSession s WHERE s.cashierId = :cashierId"
            + " AND s.status = com.imsvet.ims.finance.domain.CashSessionStatus.OPEN")
    Optional<CashSession> findOpenOf(Long cashierId);

    @Query("SELECT s FROM CashSession s ORDER BY s.id DESC")
    Page<CashSession> findNewestFirst(Pageable pageable);

    /** BR-CASH-003: only the cash method counts. */
    @Query(value = """
            SELECT COALESCE(SUM(amount), 0) FROM cash_movements
            WHERE cash_session_id = :sessionId AND method = 'cash' AND direction = :direction""", nativeQuery = true)
    BigDecimal cashTotal(long sessionId, String direction);
}
