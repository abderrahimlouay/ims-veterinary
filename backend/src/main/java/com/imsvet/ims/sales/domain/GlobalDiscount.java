package com.imsvet.ims.sales.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * ADR-175, BR-SALE-018: the global discount (remise globale) of a sales document, spread over its lines as a share of
 * each line's amount excl. tax, so the VAT is that of what the customer pays.
 * <ul>
 * <li>{@link Kind#PERCENT}: each line gives {@code ROUND(ht × value / 100, 2)}, HALF_UP.</li>
 * <li>{@link Kind#AMOUNT}: {@code value} comes off the total incl. tax. Each line first gives its pro-rata
 * {@code ROUND(ht × value / ttc, 2)}; the centimes the per-line VAT rounding leaves are then moved one by one, lowest VAT
 * rate first, until the total incl. tax is the old one less {@code value}. When no centime step can close the gap it
 * stays, at most 0.01.</li>
 * </ul>
 * A share is never negative nor above its line's amount.
 */
public record GlobalDiscount(Kind kind, BigDecimal value) {

    public enum Kind {
        PERCENT("percent"), AMOUNT("amount");

        private final String code;

        Kind(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static Optional<Kind> fromCode(String code) {
            return Arrays.stream(values()).filter(k -> k.code.equals(code)).findFirst();
        }
    }

    /** A line before the global discount: its amount excl. tax, after its own discount, and its VAT rate. */
    public record Base(BigDecimal ht, BigDecimal vatRate) {
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal CENT = new BigDecimal("0.01");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final int MONEY_SCALE = 2;

    /** The total incl. tax of {@code lines} before any global discount, each line rounded as on the document. */
    public static BigDecimal ttcOf(List<Base> lines) {
        return lines.stream().map(b -> ttc(b.ht(), b.vatRate())).reduce(ZERO, BigDecimal::add);
    }

    /** The share of each line, in the order given. */
    public List<BigDecimal> spread(List<Base> lines) {
        return kind == Kind.PERCENT ? percent(lines) : amount(lines);
    }

    private List<BigDecimal> percent(List<Base> lines) {
        return lines.stream()
                .map(b -> b.ht().multiply(value).divide(HUNDRED).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                        .min(b.ht()).max(ZERO))
                .toList();
    }

    private List<BigDecimal> amount(List<Base> lines) {
        BigDecimal total = ttcOf(lines);
        if (total.signum() <= 0) {
            return lines.stream().map(b -> ZERO).toList();
        }
        if (value.compareTo(total) >= 0) {
            return lines.stream().map(b -> b.ht().max(ZERO)).toList();
        }
        List<BigDecimal> shares = new ArrayList<>();
        for (Base b : lines) {
            shares.add(b.ht().multiply(value).divide(total, MathContext.DECIMAL128)
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP).min(b.ht()).max(ZERO));
        }
        BigDecimal gap = ttcAfter(lines, shares).subtract(total.subtract(value));
        List<Integer> order = IntStream.range(0, lines.size()).boxed()
                .sorted(Comparator.comparing((Integer i) -> lines.get(i).vatRate()).thenComparing(i -> i)).toList();
        while (gap.signum() != 0) {
            BigDecimal moved = moveOneCent(lines, shares, order, gap);
            if (moved.signum() == 0) {
                break;
            }
            gap = gap.subtract(moved);
        }
        return List.copyOf(shares);
    }

    /**
     * Moves one centime of share on the first line whose total incl. tax then moves by no more than {@code gap}; returns
     * how much the total incl. tax went down (negative when it went up), zero when no line can.
     */
    private static BigDecimal moveOneCent(List<Base> lines, List<BigDecimal> shares, List<Integer> order,
            BigDecimal gap) {
        boolean more = gap.signum() > 0;
        for (int i : order) {
            Base b = lines.get(i);
            BigDecimal share = shares.get(i);
            BigDecimal next = more ? share.add(CENT) : share.subtract(CENT);
            if (next.signum() < 0 || next.compareTo(b.ht()) > 0) {
                continue;
            }
            BigDecimal drop = ttc(b.ht().subtract(share), b.vatRate()).subtract(ttc(b.ht().subtract(next), b.vatRate()));
            if (drop.abs().compareTo(gap.abs()) <= 0) {
                shares.set(i, next);
                return drop;
            }
        }
        return ZERO;
    }

    private static BigDecimal ttcAfter(List<Base> lines, List<BigDecimal> shares) {
        BigDecimal sum = ZERO;
        for (int i = 0; i < lines.size(); i++) {
            sum = sum.add(ttc(lines.get(i).ht().subtract(shares.get(i)), lines.get(i).vatRate()));
        }
        return sum;
    }

    private static BigDecimal ttc(BigDecimal ht, BigDecimal vatRate) {
        return ht.add(SaleAmounts.lineVat(ht, vatRate));
    }
}
