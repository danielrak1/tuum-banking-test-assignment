package com.danielrak.banking;

import static com.danielrak.banking.BankingApi.JSON;
import static org.assertj.core.api.Assertions.assertThat;

import com.danielrak.banking.domain.Currency;
import com.danielrak.banking.domain.Direction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

/**
 * What a reviewer and docker compose see of the running app: the OpenAPI document behind Swagger UI
 * (intent.md, "API docs") and the health endpoint the compose healthcheck polls (design.md §1).
 */
@IntegrationTest
class OpenApiIT {

    @Autowired
    RestTestClient client;

    @Autowired
    HealthContributorRegistry healthContributors;

    @Test
    void namesEveryOperationExplicitly() {
        List<String> operationIds = new ArrayList<>();
        for (JsonNode path : apiDocs().get("paths")) {
            for (JsonNode operation : path) {
                operationIds.add(operation.get("operationId").asString());
            }
        }
        assertThat(operationIds)
                .containsExactlyInAnyOrder("createAccount", "getAccount", "createTransaction", "listTransactions");
    }

    @Test
    void showsTransactionFieldsAsRequiredWithTheirEnums() {
        JsonNode schema = apiDocs().at("/components/schemas/CreateTransactionRequest");
        assertThat(strings(schema.get("required")))
                .containsExactlyInAnyOrder("amount", "currency", "direction", "description");
        assertThat(strings(schema.at("/properties/currency/enum"))).containsExactlyElementsOf(names(Currency.values()));
        assertThat(strings(schema.at("/properties/direction/enum"))).containsExactlyElementsOf(names(Direction.values()));
    }

    /** Lengths count code points (design.md §2), as JSON Schema's maxLength does. */
    @Test
    void showsFreeTextMaxLengths() {
        JsonNode schemas = apiDocs().at("/components/schemas");
        assertThat(schemas.at("/CreateAccountRequest/properties/customerId/maxLength").asInt()).isEqualTo(64);
        assertThat(schemas.at("/CreateTransactionRequest/properties/description/maxLength").asInt()).isEqualTo(255);
    }

    @Test
    void showsAccountCurrenciesWithTheirEnum() {
        JsonNode currencies = apiDocs().at("/components/schemas/CreateAccountRequest/properties/currencies");
        assertThat(strings(currencies.at("/items/enum"))).containsExactlyElementsOf(names(Currency.values()));
        assertThat(currencies.get("uniqueItems").asBoolean()).isTrue();
    }

    @Test
    void servesSwaggerUi() {
        EntityExchangeResult<String> result = client.get().uri("/swagger-ui/index.html")
                .exchange()
                .returnResult(String.class);
        assertThat(result.getStatus().value()).isEqualTo(200);
        assertThat(result.getResponseBody()).contains("Swagger UI");
    }

    @Test
    void reportsHealthUp() {
        EntityExchangeResult<String> result = client.get().uri("/actuator/health")
                .exchange()
                .returnResult(String.class);
        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(200);
        assertThat(JSON.readTree(result.getResponseBody()).get("status").asString()).isEqualTo("UP");
    }

    /**
     * Health is app + DB only: the outbox lets the app serve through a broker outage (ADR-0003). This is
     * checked on the registry, not by pausing the broker: the rabbit indicator only reads the cached
     * connection's server properties, so it would stay UP while paused anyway.
     */
    @Test
    void leavesTheBrokerOutOfHealth() {
        assertThat(healthContributors.getContributor("db")).isNotNull();
        assertThat(healthContributors.getContributor("rabbit")).isNull();
    }

    private JsonNode apiDocs() {
        EntityExchangeResult<String> result = client.get().uri("/v3/api-docs")
                .exchange()
                .returnResult(String.class);
        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(200);
        return JSON.readTree(result.getResponseBody());
    }

    private static List<String> strings(JsonNode array) {
        assertThat(array.isArray()).as("expected a JSON array, got %s", array).isTrue();
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asString()));
        return values;
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
