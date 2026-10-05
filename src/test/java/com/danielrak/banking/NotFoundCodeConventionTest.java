package com.danielrak.banking;

import static org.assertj.core.api.Assertions.assertThat;

import com.danielrak.banking.api.NotFoundCode;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Conventions for endpoints with a UUID path variable (design.md §3):
 * <ul>
 *   <li>They need {@code @NotFoundCode}. Without it, a malformed ID gets {@code VALIDATION_FAILED}
 *       and an unknown one a 500, instead of the endpoint's code.</li>
 *   <li>The path variable comes before any {@code @RequestBody}. Spring resolves arguments in
 *       declaration order, and that is what makes a malformed ID win over a bad body (rule 1).</li>
 * </ul>
 */
@IntegrationTest
class NotFoundCodeConventionTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyHandlerWithUuidPathVariableDeclaresNotFoundCode() {
        List<HandlerMethod> withUuidPath = handlersWithUuidPathVariable();

        assertThat(withUuidPath).as("handlers with a UUID path variable").isNotEmpty();
        assertThat(withUuidPath)
                .filteredOn(h -> !h.hasMethodAnnotation(NotFoundCode.class))
                .as("handlers missing @NotFoundCode")
                .isEmpty();
    }

    @Test
    void uuidPathVariableIsDeclaredBeforeRequestBody() {
        List<HandlerMethod> withBody = handlersWithUuidPathVariable().stream()
                .filter(h -> Arrays.stream(h.getMethodParameters()).anyMatch(p -> p.hasParameterAnnotation(RequestBody.class)))
                .toList();

        assertThat(withBody).as("handlers with a UUID path variable and a request body").isNotEmpty();
        assertThat(withBody)
                .filteredOn(h -> lastIndex(h, NotFoundCodeConventionTest::isUuidPathVariable)
                        > firstIndex(h, p -> p.hasParameterAnnotation(RequestBody.class)))
                .as("handlers declaring @RequestBody before the UUID @PathVariable")
                .isEmpty();
    }

    private List<HandlerMethod> handlersWithUuidPathVariable() {
        return handlerMapping.getHandlerMethods().values().stream()
                .filter(h -> Arrays.stream(h.getMethodParameters()).anyMatch(NotFoundCodeConventionTest::isUuidPathVariable))
                .toList();
    }

    private static boolean isUuidPathVariable(MethodParameter p) {
        return p.hasParameterAnnotation(PathVariable.class) && p.getParameterType() == UUID.class;
    }

    private static int firstIndex(HandlerMethod h, Predicate<MethodParameter> test) {
        return Arrays.stream(h.getMethodParameters()).filter(test).mapToInt(MethodParameter::getParameterIndex).min().orElseThrow();
    }

    private static int lastIndex(HandlerMethod h, Predicate<MethodParameter> test) {
        return Arrays.stream(h.getMethodParameters()).filter(test).mapToInt(MethodParameter::getParameterIndex).max().orElseThrow();
    }
}
