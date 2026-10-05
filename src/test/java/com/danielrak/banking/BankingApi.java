package com.danielrak.banking;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Black-box helpers shared by the transaction integration tests. Talks to the API only through
 * HTTP, and reads {@code outbox_event} (design.md §4) for the §5 event checks.
 */
final class BankingApi {

    static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private final RestTestClient client;
    private final JdbcTemplate jdbc;

    BankingApi(RestTestClient client, JdbcTemplate jdbc) {
        this.client = client;
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- accounts

    /** Creates an account holding the given currencies and returns its ID. */
    String createAccount(String... currencies) {
        String list = String.join(", ", Stream.of(currencies).map(c -> "\"" + c + "\"").toList());
        EntityExchangeResult<String> result = client.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                      {"customerId": "C-%s", "country": "EE", "currencies": [%s]}
                      """.formatted(UUID.randomUUID(), list))
                .exchange()
                .returnResult(String.class);
        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(201);
        return JSON.readTree(result.getResponseBody()).get("accountId").asString();
    }

    JsonNode getAccount(String accountId) {
        EntityExchangeResult<String> result = client.get().uri("/accounts/{accountId}", accountId)
                .exchange()
                .returnResult(String.class);
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

    // ---------------------------------------------------------------- outbox (§4, §5)

    /** Highest outbox id for the account so far (0 if none). */
    long maxOutboxId(String accountId) {
        Long max = jdbc.queryForObject(
                "SELECT coalesce(max(id), 0) FROM outbox_event WHERE payload->>'accountId' = ?",
                Long.class, accountId);
        return max == null ? 0 : max;
    }

    /** The account's outbox rows with id greater than {@code afterId}, in id order. */
    List<Map<String, Object>> outboxRowsAfter(String accountId, long afterId) {
        return jdbc.queryForList("""
                SELECT id, event_id::text AS event_id, routing_key, payload::text AS payload
                FROM outbox_event WHERE payload->>'accountId' = ? AND id > ? ORDER BY id
                """, accountId, afterId);
    }

    void assertNoOutboxRowMentions(String marker) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE payload::text LIKE ?",
                Integer.class, "%" + marker + "%");
        assertThat(count).as("outbox rows mentioning %s", marker).isZero();
    }

    static List<String> fieldNames(JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }
}
