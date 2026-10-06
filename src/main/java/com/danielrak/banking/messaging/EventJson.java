package com.danielrak.banking.messaging;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The event serialiser. It is separate from Boot's HTTP {@code JsonMapper}, so a {@code spring.jackson.*}
 * change can't silently change the design.md §5 wire format, and every setting that shapes that format is
 * spelled out here instead of relying on defaults. Deliberately not a {@code @Bean}: a {@code JsonMapper}
 * bean would replace Boot's HTTP mapper.
 */
final class EventJson {

    static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)        // record declaration order
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)            // ISO-8601 instants
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)          // 100.00, never 1.0000E+2
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.ALWAYS))
            .build();

    private EventJson() {
    }
}
