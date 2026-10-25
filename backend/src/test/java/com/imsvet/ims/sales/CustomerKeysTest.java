package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.sales.domain.CustomerKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** ADR-110: the normalisation behind duplicate detection, one rule per case. */
class CustomerKeysTest {

    @ParameterizedTest
    @CsvSource({
            "0555 00 00 01, 555000001",
            "0555.00.00.01, 555000001",
            "0555-00-00-01, 555000001",
            "(0555) 000 001, 555000001",
            "+213 555 00 00 01, 555000001",
            "+213 (0) 555 00 00 01, 555000001",
            "00213 555 00 00 01, 555000001",
            "00 213 555 000 001, 555000001",
            "555000001, 555000001",
            "021 00 00 01, 21000001" })
    void algerianFormatsGiveTheSameNationalNumber(String phone, String key) {
        assertThat(CustomerKeys.phoneKey(phone)).isEqualTo(key);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "  ", " - . ", "+213", "phone" })
    void aPhoneWithoutDigitsHasNoKeyAndNeverConflicts(String phone) {
        assertThat(CustomerKeys.phoneKey(phone)).isNull();
    }

    @Test
    void aTaxIdIsTrimmedUpperCasedWithoutSpaces() {
        assertThat(CustomerKeys.taxKey("  nif 0012 3456 ab ")).isEqualTo("NIF00123456AB");
        assertThat(CustomerKeys.taxKey("NIF00123456AB")).isEqualTo("NIF00123456AB");
        assertThat(CustomerKeys.taxKey("nif\t0012 3456ab")).isEqualTo("NIF00123456AB");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "\t" })
    void aMissingTaxIdHasNoKeyAndNeverConflicts(String taxId) {
        assertThat(CustomerKeys.taxKey(taxId)).isNull();
    }
}
