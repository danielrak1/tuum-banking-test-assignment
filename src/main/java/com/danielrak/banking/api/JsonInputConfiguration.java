package com.danielrak.banking.api;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Strict request JSON for Boot's HTTP mapper (design.md §2, §3 rule 4). Events have their own
 * mapper ({@code EventJson}), so none of this changes the §5 wire format.
 */
@Configuration(proxyBeanMethods = false)
class JsonInputConfiguration {

    @Bean
    JsonMapperBuilderCustomizer strictJsonInput() {
        return builder -> builder
                // Otherwise the last of two "amount" keys silently wins.
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                // "10.50" or "" into BigDecimal fails; a JSON integer into BigDecimal is still accepted.
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                // 42, 1.5 or true into a String field fails instead of being stored as text.
                .withCoercionConfig(LogicalType.Textual, config -> config
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
