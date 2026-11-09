package com.imsvet.ims.sales.application;

import com.imsvet.ims.sales.domain.GlobalDiscount;
import com.imsvet.ims.sales.domain.GlobalDiscount.Base;
import com.imsvet.ims.sales.domain.SalesDocument;
import com.imsvet.ims.sales.domain.SalesDocumentLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/**
 * BR-SALE-008, ADR-115: a document's totals are the sums of its rounded lines. The global discount is spread over the
 * lines first (ADR-175, BR-SALE-018); a credit-note line keeps its credited amounts.
 */
final class SaleTotals {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private SaleTotals() {
    }

    static void apply(SalesDocument document, List<SalesDocumentLine> lines, Instant now) {
        List<Base> bases = lines.stream()
                .map(l -> new Base(l.lineHtBeforeGlobalDiscount(), l.vatRate())).toList();
        List<BigDecimal> shares = document.globalDiscount().map(d -> d.spread(bases))
                .orElseGet(() -> bases.stream().map(b -> ZERO).toList());
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).creditedHt() == null) {
                lines.get(i).takeGlobalDiscount(shares.get(i));
            }
        }
        BigDecimal ttc = sum(lines, SalesDocumentLine::lineTtc);
        BigDecimal globalHt = sum(lines, SalesDocumentLine::globalDiscountHt);
        BigDecimal globalTtc = globalHt.signum() == 0 ? ZERO : GlobalDiscount.ttcOf(bases).subtract(ttc);
        document.applyGlobalDiscountTotals(globalHt, globalTtc);
        document.applyTotals(sum(lines, SalesDocumentLine::lineHt), sum(lines, SalesDocumentLine::lineDiscount),
                sum(lines, SalesDocumentLine::lineVat), ttc, now);
    }

    /** The total incl. tax of the lines before the global discount: what an amount may take off at most. */
    static BigDecimal ttcBeforeGlobalDiscount(List<SalesDocumentLine> lines) {
        return GlobalDiscount.ttcOf(lines.stream()
                .map(l -> new Base(l.lineHtBeforeGlobalDiscount(), l.vatRate())).toList());
    }

    private static BigDecimal sum(List<SalesDocumentLine> lines, Function<SalesDocumentLine, BigDecimal> amount) {
        return lines.stream().map(amount).reduce(ZERO, BigDecimal::add);
    }
}
