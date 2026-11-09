package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.sales.domain.GlobalDiscount;
import com.imsvet.ims.sales.domain.GlobalDiscount.Base;
import com.imsvet.ims.sales.domain.GlobalDiscount.Kind;
import com.imsvet.ims.sales.domain.SaleAmounts;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** ADR-175, BR-SALE-018: how a global discount is spread over the lines. */
class GlobalDiscountTest {

    private static final List<Base> MIXED = List.of(base("2000.00", "9"), base("500.00", "19"), base("333.33", "0"));

    @Test
    void aPercentageTakesItsShareOfEachLine() {
        List<BigDecimal> shares = new GlobalDiscount(Kind.PERCENT, dec("10")).spread(MIXED);

        assertThat(shares).extracting(BigDecimal::toPlainString).containsExactly("200.00", "50.00", "33.33");
    }

    @Test
    void theUsersExampleHundredThousandLessTwoThousand() {
        List<Base> lines = List.of(base("100000.00", "0"));

        assertThat(ttcAfter(lines, new GlobalDiscount(Kind.AMOUNT, dec("2000")).spread(lines)))
                .isEqualByComparingTo("98000.00");
        assertThat(ttcAfter(lines, new GlobalDiscount(Kind.PERCENT, dec("2")).spread(lines)))
                .isEqualByComparingTo("98000.00");
    }

    @ParameterizedTest
    @CsvSource({"75.00", "0.01", "1.00", "123.45", "999.99", "1777.77", "3108.32"})
    void anAmountComesOffTheTotalInclTaxToTheCentime(String amount) {
        BigDecimal before = GlobalDiscount.ttcOf(MIXED);
        List<BigDecimal> shares = new GlobalDiscount(Kind.AMOUNT, dec(amount)).spread(MIXED);

        assertThat(ttcAfter(MIXED, shares)).isEqualByComparingTo(before.subtract(dec(amount)));
        for (int i = 0; i < MIXED.size(); i++) {
            assertThat(shares.get(i)).isBetween(BigDecimal.ZERO, MIXED.get(i).ht());
        }
    }

    @Test
    void withOnlyTaxedLinesTheGapIsAtMostOneCentime() {
        List<Base> taxed = List.of(base("10.00", "19"), base("7.77", "9"));
        BigDecimal before = GlobalDiscount.ttcOf(taxed);
        for (int cents = 1; cents < 2000; cents += 7) {
            BigDecimal amount = BigDecimal.valueOf(cents, 2);
            BigDecimal after = ttcAfter(taxed, new GlobalDiscount(Kind.AMOUNT, amount).spread(taxed));
            assertThat(after.subtract(before.subtract(amount)).abs()).isLessThanOrEqualTo(dec("0.01"));
        }
    }

    @Test
    void theWholeTotalLeavesNothingAndAnEmptyDocumentNoShare() {
        BigDecimal all = GlobalDiscount.ttcOf(MIXED);

        assertThat(new GlobalDiscount(Kind.AMOUNT, all).spread(MIXED)).extracting(BigDecimal::toPlainString)
                .containsExactly("2000.00", "500.00", "333.33");
        assertThat(new GlobalDiscount(Kind.AMOUNT, dec("10")).spread(List.of(base("0.00", "9"))))
                .extracting(BigDecimal::toPlainString).containsExactly("0.00");
        assertThat(new GlobalDiscount(Kind.PERCENT, dec("100")).spread(MIXED)).extracting(BigDecimal::toPlainString)
                .containsExactly("2000.00", "500.00", "333.33");
    }

    private static BigDecimal ttcAfter(List<Base> lines, List<BigDecimal> shares) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            BigDecimal ht = lines.get(i).ht().subtract(shares.get(i));
            sum = sum.add(ht).add(SaleAmounts.lineVat(ht, lines.get(i).vatRate()));
        }
        return sum;
    }

    private static Base base(String ht, String rate) {
        return new Base(dec(ht), dec(rate));
    }

    private static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }
}
