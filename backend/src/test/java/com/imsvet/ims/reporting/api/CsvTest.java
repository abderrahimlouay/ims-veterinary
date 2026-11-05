package com.imsvet.ims.reporting.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Unit test (U) of the CSV format of report exports (ADR-149). */
class CsvTest {

    private static final ZoneId ALGIERS = ZoneId.of("Africa/Algiers");

    @Test
    void frenchSpreadsheetFormatWithByteOrderMark() {
        byte[] bytes = new Csv(ALGIERS).row("Produit", "CA HT")
                .row("Ivermectine 1 %", new BigDecimal("-1234.50"))
                .row(LocalDate.of(2026, 11, 5), Instant.parse("2026-11-05T22:30:00Z"), 7L, true, null).bytes();

        assertThat(Arrays.copyOfRange(bytes, 0, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        assertThat(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8)).isEqualTo(
                "Produit;CA HT\r\nIvermectine 1 %;-1234,50\r\n05/11/2026;05/11/2026 23:30;7;Oui;\r\n");
    }

    @Test
    void textIsQuotedWhenNeededAndNeverRunAsAFormula() {
        String text = text(new Csv(ALGIERS).row("Ferme; \"Nord\"", "=HYPERLINK(\"x\")", "-2+3", "@SUM(A1)",
                "Ligne\nsuivante").bytes());

        assertThat(text).isEqualTo("\"Ferme; \"\"Nord\"\"\";\"'=HYPERLINK(\"\"x\"\")\";'-2+3;'@SUM(A1);"
                + "\"Ligne\nsuivante\"\r\n");
    }

    private static String text(byte[] bytes) {
        return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
    }
}
