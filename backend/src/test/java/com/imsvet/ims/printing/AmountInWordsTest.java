package com.imsvet.ims.printing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.printing.application.AmountInWords;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** ADR-141: the amount in words of invoices and credit notes, in French, traditional spelling. */
class AmountInWordsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "0|Zéro dinar algérien",
        "1|Un dinar algérien",
        "1.01|Un dinar algérien et un centime",
        "16|Seize dinars algériens",
        "17|Dix-sept dinars algériens",
        "21|Vingt et un dinars algériens",
        "22.50|Vingt-deux dinars algériens et cinquante centimes",
        "70|Soixante-dix dinars algériens",
        "71|Soixante et onze dinars algériens",
        "77|Soixante-dix-sept dinars algériens",
        "80|Quatre-vingts dinars algériens",
        "81|Quatre-vingt-un dinars algériens",
        "91|Quatre-vingt-onze dinars algériens",
        "99.99|Quatre-vingt-dix-neuf dinars algériens et quatre-vingt-dix-neuf centimes",
        "100|Cent dinars algériens",
        "101|Cent un dinars algériens",
        "200|Deux cents dinars algériens",
        "280|Deux cent quatre-vingts dinars algériens",
        "1000|Mille dinars algériens",
        "1234.56|Mille deux cent trente-quatre dinars algériens et cinquante-six centimes",
        "80000|Quatre-vingt mille dinars algériens",
        "200000|Deux cent mille dinars algériens",
        "1000000|Un million de dinars algériens",
        "2300000|Deux millions trois cent mille dinars algériens",
        "3000000000|Trois milliards de dinars algériens",
        "200000000|Deux cents millions de dinars algériens",
        "1000000001|Un milliard un dinars algériens",
        "999999999999.99|Neuf cent quatre-vingt-dix-neuf milliards neuf cent quatre-vingt-dix-neuf millions neuf cent quatre-vingt-dix-neuf mille neuf cent quatre-vingt-dix-neuf dinars algériens et quatre-vingt-dix-neuf centimes",
    })
    void writesTheAmountInFrench(String amount, String words) {
        assertThat(AmountInWords.dinars(new BigDecimal(amount))).isEqualTo(words);
    }

    @Test
    void roundsToTheCentimeAndRefusesWhatIsOutOfRange() {
        assertThat(AmountInWords.dinars(new BigDecimal("10.005"))).isEqualTo("Dix dinars algériens et un centime");
        assertThatThrownBy(() -> AmountInWords.dinars(new BigDecimal("-1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AmountInWords.dinars(new BigDecimal("1000000000000")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
