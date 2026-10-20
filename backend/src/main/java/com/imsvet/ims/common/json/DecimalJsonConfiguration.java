package com.imsvet.ims.common.json;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamWriteFeature;

/**
 * ADR-021: every {@link BigDecimal} is written as a plain JSON string ({@code "1234.50"}), keeping its scale.
 */
@Configuration(proxyBeanMethods = false)
public class DecimalJsonConfiguration {

    @Bean
    public JsonMapperBuilderCustomizer decimalsAsStrings() {
        return builder -> builder
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .withConfigOverride(BigDecimal.class,
                        o -> o.setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING)));
    }
}
