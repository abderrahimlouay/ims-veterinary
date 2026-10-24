package com.imsvet.ims.inventory.application;

import com.imsvet.ims.common.security.InternalService;
import com.imsvet.ims.inventory.domain.Lot;
import com.imsvet.ims.inventory.infrastructure.LotRepository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lot number, expiry and stored status by id, for documents of other modules that name their lots (receipts). */
@Service
@InternalService
public class LotReferences {

    public record LotRef(long id, String lotNumber, LocalDate expiryDate, String status) {
    }

    private final LotRepository lots;

    public LotReferences(LotRepository lots) {
        this.lots = lots;
    }

    @Transactional(readOnly = true)
    public Map<Long, LotRef> find(Collection<Long> ids) {
        return lots.findAllByIdIn(ids).stream().map(LotReferences::ref)
                .collect(Collectors.toMap(LotRef::id, Function.identity()));
    }

    private static LotRef ref(Lot lot) {
        return new LotRef(lot.id(), lot.lotNumber(), lot.expiryDate(), lot.status().code());
    }
}
