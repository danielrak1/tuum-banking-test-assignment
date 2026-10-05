package com.danielrak.banking;

import static org.assertj.core.api.Assertions.assertThat;

import com.danielrak.banking.api.NotFoundCode;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every endpoint with a UUID path variable needs {@code @NotFoundCode}: without it, a malformed ID
 * gets {@code VALIDATION_FAILED} and an unknown one a 500, instead of the endpoint's §3 code.
 */
@IntegrationTest
class NotFoundCodeConventionTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyHandlerWithUuidPathVariableDeclaresNotFoundCode() {
        List<HandlerMethod> withUuidPath = handlerMapping.getHandlerMethods().values().stream()
                .filter(h -> Arrays.stream(h.getMethodParameters()).anyMatch(
                        p -> p.hasParameterAnnotation(PathVariable.class) && p.getParameterType() == UUID.class))
                .toList();

        assertThat(withUuidPath).as("handlers with a UUID path variable").isNotEmpty();
        assertThat(withUuidPath)
                .filteredOn(h -> !h.hasMethodAnnotation(NotFoundCode.class))
                .as("handlers missing @NotFoundCode")
                .isEmpty();
    }
}
