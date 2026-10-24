package com.imsvet.ims.inventory.application;

import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.inventory.domain.Lot;
import com.imsvet.ims.inventory.infrastructure.LotLocks;
import com.imsvet.ims.inventory.infrastructure.LotRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Find-or-create of a lot by (product, lot number) under lock (BR-LOT-002, ADR-070), for the flows that bring stock
 * in: opening stock (Phase 4), lots found during a count (ADR-074), receipts (Phase 5). An existing lot must have
 * the same expiry date ({@code LOT_EXPIRY_MISMATCH}); the caller decides what else it requires. Runs in the caller's
 * transaction; the lot starts empty, its stock comes from a movement posted by {@code StockService}.
 */
@Component
public class LotIdentities {

    /** The locked lot and whether this call created it. */
    public record Found(Lot lot, boolean created) {
    }

    private final LotRepository lots;
    private final LotLocks lotLocks;
    private final Clock clock;

    public LotIdentities(LotRepository lots, LotLocks lotLocks, Clock clock) {
        this.lots = lots;
        this.lotLocks = lotLocks;
        this.clock = clock;
    }

    /**
     * The identity of a lot as the unique key sees it (lot number ignoring case and accents, ADR-058): two entries
     * with the same key are the same lot.
     */
    public static String key(long productId, String lotNumber) {
        String folded = Normalizer.normalize(lotNumber.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return productId + "/" + folded.toUpperCase(Locale.ROOT);
    }

    /** MANDATORY: LAST_INSERT_ID is per connection, so reset, insert and read must share the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Found findOrCreate(long productId, String lotNumber, LocalDate expiryDate, BigDecimal unitCost) {
        lots.resetLastInsertId();
        lots.insertIfAbsent(productId, lotNumber, expiryDate, unitCost, clock.instant());
        boolean created = lots.lastInsertId() != 0;
        Lot lot = lotLocks.lock(lots.findByIdentity(productId, lotNumber).orElseThrow().id()).orElseThrow();
        if (!lot.expiryDate().equals(expiryDate)) {
            throw new BusinessException(ErrorCode.LOT_EXPIRY_MISMATCH,
                    "This lot number already exists with another expiry date.",
                    Map.of("lotId", lot.id(), "expiryDate", lot.expiryDate().toString()));
        }
        return new Found(lot, created);
    }
}
