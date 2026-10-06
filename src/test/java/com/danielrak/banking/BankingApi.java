package com.danielrak.banking;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Black-box HTTP helpers shared by the integration tests: create and read accounts, post and list
 * transactions, and assert §3 problems. It talks to the API only through HTTP; the §5 event checks
 * live in {@link BankingEvents}, which reads the {@code banking.events.all} queue.
 */
final class BankingApi {

    static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private final RestTestClient client;

    BankingApi(RestTestClient client) {
        this.client = client;
    }

    // ---------------------------------------------------------------- accounts

    /** A customer ID no other test uses, so it can serve as an event marker. */
    static String uniqueCustomerId() {
        return "C-" + UUID.randomUUID();
    }

    EntityExchangeResult<String> postAccountRaw(String json) {
        return client.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .exchange()
                .returnResult(String.class);
    }

    /** Creates an account holding the given currencies and returns its ID. */
    String createAccount(String... currencies) {
        String list = String.join(", ", Stream.of(currencies).map(c -> "\"" + c + "\"").toList());
        EntityExchangeResult<String> result = postAccountRaw("""
                {"customerId": "%s", "country": "EE", "currencies": [%s]}
                """.formatted(uniqueCustomerId(), list));
        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(201);
        return JSON.readTree(result.getResponseBody()).get("accountId").asString();
    }

    EntityExchangeResult<String> getAccountRaw(String accountId) {
        return client.get().uri("/accounts/{accountId}", accountId)
                .exchange()
                .returnResult(String.class);
    }

    JsonNode getAccount(String accountId) {
        EntityExchangeResult<String> result = getAccountRaw(accountId);
        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(200);
        return JSON.readTree(result.getResponseBody());
    }

    /** The stored available amount for one currency, read through GET /accounts/{id}. */
    BigDecimal balance(String accountId, String currency) {
        for (JsonNode balance : getAccount(accountId).get("balances")) {
            if (currency.equals(balance.get("currency").asString())) {
                return balance.get("availableAmount").decimalValue();
            }
        }
        throw new AssertionError("account " + accountId + " has no " + currency + " balance");
    }

    // ---------------------------------------------------------------- transactions

    EntityExchangeResult<String> postTransactionRaw(String accountId, String json) {
        return client.post().uri("/accounts/{accountId}/transactions", accountId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .exchange()
                .returnResult(String.class);
    }

    /** Sends a well-formed transaction body; {@code amount} is written verbatim as a JSON number. */
    EntityExchangeResult<String> postTransaction(
            String accountId, String amount, String currency, String direction, String description) {
        return postTransactionRaw(accountId, """
                {"amount": %s, "currency": "%s", "direction": "%s", "description": "%s"}
                """.formatted(amount, currency, direction, description));
    }

    /** Posts a transaction that must succeed, and returns the response body. */
    JsonNode transact(String accountId, String amount, String currency, String direction, String description) {
        EntityExchangeResult<String> result = postTransaction(accountId, amount, currency, direction, description);
        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(201);
        return JSON.readTree(result.getResponseBody());
    }

    EntityExchangeResult<String> getTransactionsRaw(String accountId) {
        return client.get().uri("/accounts/{accountId}/transactions", accountId)
                .exchange()
                .returnResult(String.class);
    }

    List<JsonNode> listTransactions(String accountId) {
        EntityExchangeResult<String> result = getTransactionsRaw(accountId);
        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(200);
        JsonNode body = JSON.readTree(result.getResponseBody());
        assertThat(body.isArray()).as("body is a JSON array: %s", body).isTrue();
        List<JsonNode> list = new ArrayList<>();
        body.forEach(list::add);
        return list;
    }

    // ---------------------------------------------------------------- problems (§3)

    static JsonNode expectProblem(EntityExchangeResult<String> result, int status, String code) {
        String diagnostics = "status=%s content-type=%s body=%s".formatted(
                result.getStatus(), result.getResponseHeaders().getContentType(), result.getResponseBody());
        assertThat(result.getStatus().value()).as(diagnostics).isEqualTo(status);
        MediaType contentType = result.getResponseHeaders().getContentType();
        assertThat(contentType).as(diagnostics).isNotNull();
        assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).as(diagnostics).isTrue();
        assertThat(result.getResponseBody()).as(diagnostics).isNotBlank();
        JsonNode problem = JSON.readTree(result.getResponseBody());
        assertThat(problem.path("status").asInt()).as(diagnostics).isEqualTo(status);
        assertThat(problem.path("code").asString()).as(diagnostics).isEqualTo(code);
        return problem;
    }

    static List<JsonNode> errors(JsonNode problem) {
        JsonNode errors = problem.get("errors");
        assertThat(errors).as("errors[] present in %s", problem).isNotNull();
        assertThat(errors.isArray()).as("errors[] is an array in %s", problem).isTrue();
        List<JsonNode> list = new ArrayList<>();
        errors.forEach(list::add);
        return list;
    }

    /** Asserts errors[] is exactly these (field, code) pairs, in this order. */
    static void assertErrors(JsonNode problem, String... fieldCodePairs) {
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < fieldCodePairs.length; i += 2) {
            expected.add(fieldCodePairs[i] + "=" + fieldCodePairs[i + 1]);
        }
        List<String> actual = errors(problem).stream()
                .map(e -> e.path("field").asString() + "=" + e.path("code").asString())
                .toList();
        assertThat(actual).as("errors[] of %s", problem).containsExactlyElementsOf(expected);
    }

    static void assertJsonContentType(EntityExchangeResult<String> result) {
        MediaType contentType = result.getResponseHeaders().getContentType();
        assertThat(contentType).isNotNull();
        assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_JSON))
                .as("Content-Type %s", contentType).isTrue();
    }

    static List<String> fieldNames(JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }
}
