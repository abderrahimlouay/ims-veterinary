package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.sales.domain.ReturnCredit;
import com.imsvet.ims.sales.domain.ReturnCredit.Credit;
import com.imsvet.ims.sales.domain.ReturnCredit.SoldLine;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * ADR-130, BR-RET-004 (U): a partial return credits the pro-rata of the line's unrounded amount and its VAT per line;
 * the return that completes the line credits the exact remainder, so the credits of a line add up to what was invoiced.
 */
class ReturnCreditTest {

    @Test
    void threeReturnsOfOneOutOfThreeAt0335CreditExactlyTheInvoicedLine() {
        // Invoiced: 3 × 0.335 = 1.005 → 1.01 HT, VAT 19 % → 0.19.
        Credit first = ReturnCredit.of(sold("3", "3", "0.3350", "0", "19", "0", "0.00", "0.00"), dec("1"));
        Credit second = ReturnCredit.of(sold("3", "3", "0.3350", "0", "19", "1", "0.34", "0.06"), dec("1"));
        Credit last = ReturnCredit.of(sold("3", "3", "0.3350", "0", "19", "2", "0.68", "0.12"), dec("1"));

        assertThat(first.ht()).isEqualByComparingTo("0.34");
        assertThat(first.vat()).isEqualByComparingTo("0.06");
        assertThat(second.ht()).isEqualByComparingTo("0.34");
        assertThat(last.ht()).isEqualByComparingTo("0.33");
        assertThat(last.vat()).isEqualByComparingTo("0.07");
        assertThat(first.ht().add(second.ht()).add(last.ht())).isEqualByComparingTo("1.01");
        assertThat(first.vat().add(second.vat()).add(last.vat())).isEqualByComparingTo("0.19");
    }

    @Test
    void aWholeReturnCreditsTheLineAsInvoiced() {
        Credit all = ReturnCredit.of(sold("2", "20", "12.5000", "10", "9", "0", "0.00", "0.00"), dec("20"));

        assertThat(all.quantity()).isEqualByComparingTo("2");
        assertThat(all.ht()).isEqualByComparingTo("22.50");
        assertThat(all.vat()).isEqualByComparingTo("2.03");
    }

    @Test
    void aPartReturnedInBaseUnitsIsTheProRataOfTheUnroundedLineWithItsDiscount() {
        // 2 boxes of 10 at 12.50, 10 % off: 22.50 HT; 3 tablets = 3/20 → 3.375 → 3.38 HT, VAT 9 % → 0.30.
        Credit part = ReturnCredit.of(sold("2", "20", "12.5000", "10", "9", "0", "0.00", "0.00"), dec("3"));

        assertThat(part.quantity()).isEqualByComparingTo("0.300");
        assertThat(part.ht()).isEqualByComparingTo("3.38");
        assertThat(part.vat()).isEqualByComparingTo("0.30");
    }

    @Test
    void aPartialCreditNeverExceedsWhatIsLeftToCredit() {
        // 4 × 0.3333 = 1.3332 → 1.33 HT; one part is 0.3333 → 0.33. With 0.99 credited, 0.34 is left: 0.33 stays.
        Credit uncapped = ReturnCredit.of(sold("4", "4", "0.3333", "0", "0", "2", "0.99", "0.00"), dec("1"));
        assertThat(uncapped.ht()).isEqualByComparingTo("0.33");

        // With 1.30 credited only 0.03 is left: the part is capped at it.
        Credit capped = ReturnCredit.of(sold("4", "4", "0.3333", "0", "0", "2", "1.30", "0.00"), dec("1"));
        assertThat(capped.ht()).isEqualByComparingTo("0.03");
    }

    @Test
    void aTinyPartOfALargeUnitStillShowsAPositiveQuantity() {
        Credit tiny = ReturnCredit.of(sold("1", "10000", "100.0000", "0", "0", "0", "0.00", "0.00"), dec("1"));

        assertThat(tiny.quantity()).isEqualByComparingTo("0.001");
        assertThat(tiny.ht()).isEqualByComparingTo("0.01");
    }

    @Test
    void aGlobalDiscountShareIsCreditedInProportion() {
        // 3 × 100.00, 19 % VAT, global discount share 30.00: the line was 270.00 excl. tax and 51.30 VAT
        SoldLine line = sold("3", "3", "100.0000", "0", "19", "0", "0.00", "0.00", "30.00");

        Credit one = ReturnCredit.of(line, dec("1"));
        Credit all = ReturnCredit.of(line, dec("3"));

        assertThat(one.ht()).isEqualByComparingTo("90.00");
        assertThat(one.vat()).isEqualByComparingTo("17.10");
        assertThat(all.ht()).isEqualByComparingTo("270.00");
        assertThat(all.vat()).isEqualByComparingTo("51.30");
    }

    private static SoldLine sold(String quantity, String base, String price, String discount, String vat,
            String returned, String creditedHt, String creditedVat) {
        return sold(quantity, base, price, discount, vat, returned, creditedHt, creditedVat, "0.00");
    }

    private static SoldLine sold(String quantity, String base, String price, String discount, String vat,
            String returned, String creditedHt, String creditedVat, String globalDiscountHt) {
        return new SoldLine(dec(quantity), dec(base), dec(price), dec(discount), dec(vat), dec(returned),
                dec(creditedHt), dec(creditedVat), dec(globalDiscountHt));
    }

    private static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }
}
