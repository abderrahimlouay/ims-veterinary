package com.imsvet.ims.sales.application;

import com.imsvet.ims.sales.domain.SalesDocument;
import com.imsvet.ims.sales.domain.SalesDocumentLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/** BR-SALE-008, ADR-115: a document's totals are the sums of its rounded lines. */
final class SaleTotals {

    private SaleTotals() {
    }

    static void apply(SalesDocument document, List<SalesDocumentLine> lines, Instant now) {
        document.applyTotals(sum(lines, SalesDocumentLine::lineHt), sum(lines, SalesDocumentLine::lineDiscount),
                sum(lines, SalesDocumentLine::lineVat), sum(lines, SalesDocumentLine::lineTtc), now);
    }

    private static BigDecimal sum(List<SalesDocumentLine> lines, Function<SalesDocumentLine, BigDecimal> amount) {
        return lines.stream().map(amount).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }
}
