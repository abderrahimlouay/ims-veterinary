package com.imsvet.ims.inventory.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * BR-LOT-005: First Expired, First Out. Among sellable lots (BR-STOCK-011) ordered by expiry date then id, takes
 * what each lot holds until the requested base quantity is covered; what remains is the shortfall. Pure: it
 * neither reads nor locks anything (the caller locks lots in id order when it consumes them, ADR-012).
 */
public final class FefoAllocator {

    /** A lot as the allocator sees it. */
    public record Candidate(long id, LocalDate expiryDate, BigDecimal quantityOnHand, LotStatus status) {
    }

    /** One proposed lot: the quantity taken from it, in base units (3 decimals). */
    public record Line(Candidate candidate, BigDecimal quantityBase) {
    }

    public record Allocation(List<Line> lines, BigDecimal shortfallBase) {
    }

    /** Base quantities keep 3 decimals (database.md §2.3). */
    private static final int BASE_SCALE = 3;

    private static final Comparator<Candidate> FEFO_ORDER =
            Comparator.comparing(Candidate::expiryDate).thenComparingLong(Candidate::id);

    private FefoAllocator() {
    }

    public static Allocation allocate(List<Candidate> lots, BigDecimal requestedBase, ExpiryPolicy policy) {
        BigDecimal remaining = requestedBase.setScale(BASE_SCALE);
        List<Line> lines = new ArrayList<>();
        for (Candidate lot : lots.stream().sorted(FEFO_ORDER).toList()) {
            if (remaining.signum() == 0) {
                break;
            }
            if (!policy.sellable(lot.status(), lot.expiryDate(), lot.quantityOnHand())) {
                continue;
            }
            BigDecimal taken = lot.quantityOnHand().min(remaining).setScale(BASE_SCALE);
            lines.add(new Line(lot, taken));
            remaining = remaining.subtract(taken);
        }
        return new Allocation(List.copyOf(lines), remaining);
    }
}
