package com.imsvet.ims.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.catalog.domain.BarcodeFormat;
import com.imsvet.ims.catalog.domain.BarcodeFormat.Problem;
import com.imsvet.ims.catalog.domain.BarcodeType;
import org.junit.jupiter.api.Test;

/** BR-PROD-008 and ADR-067: format checks per barcode type; the EAN-13 check digit only for EAN13. */
class BarcodeFormatTest {

    @Test
    void ean13NeedsThirteenDigitsWithAValidCheckDigit() {
        assertThat(BarcodeFormat.check(BarcodeType.EAN13, "4006381333931")).isEmpty();
        assertThat(BarcodeFormat.check(BarcodeType.EAN13, "5901234123457")).isEmpty();
        assertThat(BarcodeFormat.check(BarcodeType.EAN13, "4006381333932")).contains(Problem.CHECK_DIGIT);
        assertThat(BarcodeFormat.check(BarcodeType.EAN13, "400638133393")).contains(Problem.FORMAT);
        assertThat(BarcodeFormat.check(BarcodeType.EAN13, "40063813339A1")).contains(Problem.FORMAT);
        assertThat(BarcodeFormat.check(BarcodeType.EAN13, "４００６３８１３３３９３１")).contains(Problem.FORMAT);
    }

    @Test
    void code128AcceptsPrintableAsciiOnly() {
        assertThat(BarcodeFormat.check(BarcodeType.CODE128, "AMX-10/500 mg")).isEmpty();
        assertThat(BarcodeFormat.check(BarcodeType.CODE128, "4006381333932")).isEmpty();
        assertThat(BarcodeFormat.check(BarcodeType.CODE128, "Amoxicilline é")).contains(Problem.FORMAT);
        assertThat(BarcodeFormat.check(BarcodeType.CODE128, "TAB\tX")).contains(Problem.FORMAT);
    }

    @Test
    void qrAcceptsAnyTextWithoutControlCharacters() {
        assertThat(BarcodeFormat.check(BarcodeType.QR, "https://example.org/lot?id=é")).isEmpty();
        assertThat(BarcodeFormat.check(BarcodeType.QR, "line\nbreak")).contains(Problem.FORMAT);
    }

    @Test
    void everyCodeIsNonBlankAndAtMost64Characters() {
        assertThat(BarcodeFormat.check(BarcodeType.QR, " ")).contains(Problem.REQUIRED);
        assertThat(BarcodeFormat.check(BarcodeType.QR, "")).contains(Problem.REQUIRED);
        assertThat(BarcodeFormat.check(BarcodeType.QR, "x".repeat(64))).isEmpty();
        assertThat(BarcodeFormat.check(BarcodeType.QR, "x".repeat(65))).contains(Problem.SIZE);
    }
}
