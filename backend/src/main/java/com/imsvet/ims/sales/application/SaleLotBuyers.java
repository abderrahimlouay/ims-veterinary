package com.imsvet.ims.sales.application;

import com.imsvet.ims.common.security.InternalService;
import com.imsvet.ims.inventory.application.LotBuyers;
import com.imsvet.ims.sales.infrastructure.SalesDocumentLineRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The confirmed documents that took stock of a lot (ADR-117): delivery notes invoiced or not, receipts and direct
 * invoices, anonymous ones included. A delivery note's invoice posted nothing, so its note alone is listed; a cancelled
 * document returned its stock and is not. The caller checks its permission.
 */
@Service
@InternalService
public class SaleLotBuyers implements LotBuyers {

    private final SalesDocumentLineRepository lines;

    public SaleLotBuyers(SalesDocumentLineRepository lines) {
        this.lines = lines;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LotSale> salesOf(long lotId) {
        return lines.salesOfLot(lotId).stream().map(s -> new LotSale(s.getCustomerId(), s.getCustomerName(),
                s.getPhone(), s.getDocumentNumber(), s.getDocumentType().code(), s.getIssuedAt(),
                s.getQuantityBase())).toList();
    }
}
