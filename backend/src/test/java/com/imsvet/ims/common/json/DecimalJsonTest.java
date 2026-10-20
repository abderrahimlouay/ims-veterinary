package com.imsvet.ims.common.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-021: decimals travel as JSON strings, never in exponent notation, and are parsed exactly.
 */
class DecimalJsonTest {

    record Amount(BigDecimal value) {
    }

    private final JsonMapper mapper = mapper();

    private static JsonMapper mapper() {
        JsonMapper.Builder builder = JsonMapper.builder();
        new DecimalJsonConfiguration().decimalsAsStrings().customize(builder);
        return builder.build();
    }

    @Test
    void serialisesBigDecimalAsStringKeepingScale() {
        assertThat(mapper.writeValueAsString(new Amount(new BigDecimal("1234.50"))))
                .isEqualTo("{\"value\":\"1234.50\"}");
    }

    @Test
    void neverUsesExponentNotation() {
        assertThat(mapper.writeValueAsString(new Amount(new BigDecimal("1E+3"))))
                .isEqualTo("{\"value\":\"1000\"}");
    }

    @Test
    void parsesStringIntoExactBigDecimal() {
        assertThat(mapper.readValue("{\"value\":\"12.500\"}", Amount.class).value())
                .isEqualByComparingTo("12.5")
                .hasScaleOf(3);
    }

    @Test
    void parsesJsonNumberWithoutBinaryRounding() {
        assertThat(mapper.readValue("{\"value\":0.1}", Amount.class).value()).isEqualTo(new BigDecimal("0.1"));
    }
}
