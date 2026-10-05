package com.danielrak.banking;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/** Black-box contract tests for POST /accounts and GET /accounts/{accountId}: design.md §2 (API), §3 (errors), §5 (events). */
@IntegrationTest
class AccountApiIT {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    @Autowired
    RestTestClient client;

    @Autowired
    JdbcTemplate jdbc;

    // ---------------------------------------------------------------- success (§2)

    @Test
    void createsAccountWithLocationAndZeroBalancesOrderedByCurrency() {
        String customerId = uniqueCustomerId();
        EntityExchangeResult<String> result = post("""
                {"customerId": "%s", "country": "EE", "currencies": ["USD", "SEK", "EUR", "GBP"]}
                """.formatted(customerId));

        assertThat(result.getStatus().value()).isEqualTo(201);
        JsonNode body = JSON.readTree(result.getResponseBody());

        String accountId = body.get("accountId").asString();
        assertThat(UUID.fromString(accountId).toString()).isEqualTo(accountId);
        assertThat(body.get("customerId").asString()).isEqualTo(customerId);
        assertThat(body.get("country").asString()).isEqualTo("EE");

        String location = result.getResponseHeaders().getFirst(HttpHeaders.LOCATION);
        assertThat(location).isNotNull().endsWith("/accounts/" + accountId);

        JsonNode balances = body.get("balances");
        assertThat(balances.size()).isEqualTo(4);
        List<String> currencies = new ArrayList<>();
        for (JsonNode balance : balances) {
            currencies.add(balance.get("currency").asString());
            JsonNode amount = balance.get("availableAmount");
            assertThat(amount.isNumber()).as("availableAmount is a JSON number").isTrue();
            assertThat(amount.decimalValue()).isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(amount.decimalValue().scale()).isEqualTo(2);
        }
        assertThat(currencies).containsExactly("EUR", "GBP", "SEK", "USD");
    }

    @Test
    void createsAccountWithSingleCurrency() {
        JsonNode body = createAccount(uniqueCustomerId(), "SE", "SEK");
        JsonNode balances = body.get("balances");
        assertThat(balances.size()).isEqualTo(1);
        assertThat(balances.get(0).get("currency").asString()).isEqualTo("SEK");
        assertThat(balances.get(0).get("availableAmount").decimalValue())
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void acceptsCustomerIdOfExactly64Characters() {
        String customerId = "C" + "x".repeat(55) + randomSuffix(8);
        assertThat(customerId).hasSize(64);
        JsonNode body = createAccount(customerId, "EE", "EUR");
        assertThat(body.get("customerId").asString()).isEqualTo(customerId);
    }

    // ---------------------------------------------------------------- INVALID_CURRENCY (§3)

    static Stream<Arguments> invalidCurrencyElements() {
        return Stream.of(
                Arguments.of("unsupported JPY", "[\"JPY\"]", "currencies[0]"),
                Arguments.of("lowercase eur", "[\"eur\"]", "currencies[0]"),
                Arguments.of("null element", "[null]", "currencies[0]"),
                Arguments.of("unsupported among valid", "[\"EUR\", \"JPY\"]", "currencies[1]"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCurrencyElements")
    void rejectsUnsupportedOrNullCurrencyElementWithInvalidCurrency(String label, String currencies, String field) {
        String customerId = uniqueCustomerId();
        JsonNode problem = expectProblem(post("""
                {"customerId": "%s", "country": "EE", "currencies": %s}
                """.formatted(customerId, currencies)), 400, "INVALID_CURRENCY");

        List<JsonNode> errors = errors(problem);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("field").asString()).isEqualTo(field);
        assertThat(errors.get(0).get("code").asString()).isEqualTo("INVALID_CURRENCY");
        assertNoOutboxRowMentions(customerId);
    }

    // ---------------------------------------------------------------- VALIDATION_FAILED (§3)

    static Stream<Arguments> validationFailures() {
        String c = uniqueCustomerId();
        String longId = c + "x".repeat(65 - c.length());
        return Stream.of(
                Arguments.of("empty currencies",
                        "{\"customerId\": \"%s\", \"country\": \"EE\", \"currencies\": []}".formatted(c), "currencies", c),
                Arguments.of("duplicate currencies",
                        "{\"customerId\": \"%s\", \"country\": \"EE\", \"currencies\": [\"EUR\", \"EUR\"]}".formatted(c), "currencies", c),
                Arguments.of("absent currencies",
                        "{\"customerId\": \"%s\", \"country\": \"EE\"}".formatted(c), "currencies", c),
                Arguments.of("null currencies",
                        "{\"customerId\": \"%s\", \"country\": \"EE\", \"currencies\": null}".formatted(c), "currencies", c),
                Arguments.of("lowercase country",
                        "{\"customerId\": \"%s\", \"country\": \"ee\", \"currencies\": [\"EUR\"]}".formatted(c), "country", c),
                Arguments.of("three-letter country",
                        "{\"customerId\": \"%s\", \"country\": \"EST\", \"currencies\": [\"EUR\"]}".formatted(c), "country", c),
                Arguments.of("absent country",
                        "{\"customerId\": \"%s\", \"currencies\": [\"EUR\"]}".formatted(c), "country", c),
                Arguments.of("blank customerId",
                        "{\"customerId\": \"   \", \"country\": \"EE\", \"currencies\": [\"EUR\"]}", "customerId", null),
                Arguments.of("empty customerId",
                        "{\"customerId\": \"\", \"country\": \"EE\", \"currencies\": [\"EUR\"]}", "customerId", null),
                Arguments.of("absent customerId",
                        "{\"country\": \"EE\", \"currencies\": [\"EUR\"]}", "customerId", null),
                Arguments.of("65-character customerId",
                        "{\"customerId\": \"%s\", \"country\": \"EE\", \"currencies\": [\"EUR\"]}".formatted(longId),
                        "customerId", longId),
                // §2 free-text rule: control characters U+0000–U+001F and U+007F, sent as JSON escapes
                // (a raw C0 control inside a JSON string is malformed JSON, a different case).
                Arguments.of("NUL in customerId",
                        "{\"customerId\": \"%s\\u0000x\", \"country\": \"EE\", \"currencies\": [\"EUR\"]}".formatted(c),
                        "customerId", c),
                Arguments.of("BEL in customerId",
                        "{\"customerId\": \"%s\\u0007\", \"country\": \"EE\", \"currencies\": [\"EUR\"]}".formatted(c),
                        "customerId", c),
                Arguments.of("tab in customerId",
                        "{\"customerId\": \"%s\\tx\", \"country\": \"EE\", \"currencies\": [\"EUR\"]}".formatted(c),
                        "customerId", c),
                Arguments.of("U+001F in customerId",
                        "{\"customerId\": \"%s\\u001F\", \"country\": \"EE\", \"currencies\": [\"EUR\"]}".formatted(c),
                        "customerId", c),
                Arguments.of("DEL escaped in customerId",
                        "{\"customerId\": \"%s\\u007f\", \"country\": \"EE\", \"currencies\": [\"EUR\"]}".formatted(c),
                        "customerId", c),
                Arguments.of("DEL raw in customerId",
                        "{\"customerId\": \"%s\u007Fx\", \"country\": \"EE\", \"currencies\": [\"EUR\"]}".formatted(c),
                        "customerId", c),
                // §2 free-text rule: C1 controls, line/paragraph separators and unpaired UTF-16
                // surrogates, sent as JSON escapes so the exact UTF-16 reaches the server.
                Arguments.of("NEL U+0085 in customerId",
                        "{\"customerId\": \"%sx\\u0085y\", \"country\": \"EE\", \"currencies\": [\"EUR\"]}".formatted(c),
                        "customerId", c),
                Arguments.of("U+009F in customerId",
                        "{\"customerId\": \"%sx\\u009Fy\", \"country\": \"EE\", \"currencies\": [\"EUR\"]}".formatted(c),
                        "customerId", c),
                Arguments.of("U+2028 in customerId",
                        "{\"customerId\": \"%sx\\u2028y\", \"country\": \"EE\", \"currencies\": [\"EUR\"]}".formatted(c),
                        "customerId", c),
                Arguments.of("U+2029 in customerId",
                        "{\"customerId\": \"%sx\\u2029y\", \"country\": \"EE\", \"currencies\": [\"EUR\"]}".formatted(c),
                        "customerId", c),
                Arguments.of("unpaired high surrogate in customerId",
                        "{\"customerId\": \"%sx\\ud800y\", \"country\": \"EE\", \"currencies\": [\"EUR\"]}".formatted(c),
                        "customerId", c),
                Arguments.of("unpaired low surrogate in customerId",
                        "{\"customerId\": \"%sx\\udc00y\", \"country\": \"EE\", \"currencies\": [\"EUR\"]}".formatted(c),
                        "customerId", c),
                Arguments.of("reversed surrogate pair in customerId",
                        "{\"customerId\": \"%sx\\ude00\\ud83dy\", \"country\": \"EE\", \"currencies\": [\"EUR\"]}".formatted(c),
                        "customerId", c));
    }

    @Test
    void acceptsCustomerIdWithSurrogatePairAndStoresItUnchanged() {
        // §2: free text is stored exactly as sent; a valid surrogate pair (an emoji) is not rejected.
        String prefix = uniqueCustomerId();
        String customerId = prefix + " Pay 😀";
        EntityExchangeResult<String> result = post("""
                {"customerId": "%s Pay \\ud83d\\ude00", "country": "EE", "currencies": ["EUR"]}
                """.formatted(prefix));
        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(201);
        JsonNode body = JSON.readTree(result.getResponseBody());
        String accountId = body.get("accountId").asString();
        assertThat(body.get("customerId").asString()).isEqualTo(customerId);

        JsonNode fetched = JSON.readTree(get(accountId).getResponseBody());
        assertThat(fetched.get("customerId").asString()).isEqualTo(customerId);

        String eventCustomerId = jdbc.queryForObject("""
                SELECT payload->'data'->>'customerId' FROM outbox_event
                WHERE payload->>'accountId' = ? AND routing_key = 'account.created'
                """, String.class, accountId);
        assertThat(eventCustomerId).isEqualTo(customerId);
    }

    @Test
    @Disabled("task 6: reject scalar coercion into strings, §3 rule 4")
    void rejectsNumericCustomerIdWithValidationFailed() {
        JsonNode problem = expectProblem(post("""
                {"customerId": 12345, "country": "EE", "currencies": ["EUR"]}
                """), 400, "VALIDATION_FAILED");
        List<JsonNode> errors = errors(problem);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("field").asString()).isEqualTo("customerId");
        assertThat(errors.get(0).get("code").asString()).isEqualTo("VALIDATION_FAILED");
        Integer created = jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event
                WHERE routing_key = 'account.created' AND payload->'data'->>'customerId' = '12345'
                """, Integer.class);
        assertThat(created).isZero();
    }

    @Test
    void acceptsNonAsciiCustomerIdAndStoresItUnchanged() {
        // §2: the free-text rule rejects only control characters; other Unicode is valid.
        String customerId = "Jüri Õun " + randomSuffix(8);
        JsonNode body = createAccount(customerId, "EE", "EUR");
        String accountId = body.get("accountId").asString();
        assertThat(body.get("customerId").asString()).isEqualTo(customerId);

        JsonNode fetched = JSON.readTree(get(accountId).getResponseBody());
        assertThat(fetched.get("customerId").asString()).isEqualTo(customerId);

        String eventCustomerId = jdbc.queryForObject("""
                SELECT payload->'data'->>'customerId' FROM outbox_event
                WHERE payload->>'accountId' = ? AND routing_key = 'account.created'
                """, String.class, accountId);
        assertThat(eventCustomerId).isEqualTo(customerId);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validationFailures")
    void rejectsInvalidFieldWithValidationFailed(String label, String json, String field, String marker) {
        JsonNode problem = expectProblem(post(json), 400, "VALIDATION_FAILED");

        List<JsonNode> errors = errors(problem);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("field").asString()).isEqualTo(field);
        assertThat(errors.get(0).get("code").asString()).isEqualTo("VALIDATION_FAILED");
        if (marker != null) {
            assertNoOutboxRowMentions(marker);
        } else {
            assertNoAccountCreatedWithBlankCustomerId();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"customerId\": \"%s\", \"country\": \"EE\", \"currencies\": [\"EUR\"]",
            "{\"customerId\": \"%s\" \"country\": \"EE\"}",
            "not json %s"})
    void rejectsMalformedJsonWithValidationFailed(String template) {
        String marker = uniqueCustomerId();
        expectProblem(post(template.formatted(marker)), 400, "VALIDATION_FAILED");
        assertNoOutboxRowMentions(marker);
    }

    // ---------------------------------------------------------------- several failures (§3 rule 3)

    @Test
    void reportsAllFailingFieldsWithCurrencyFirstAndFullProblemShape() {
        JsonNode problem = expectProblem(post("""
                {"customerId": "", "country": "ee", "currencies": ["JPY"]}
                """), 400, "INVALID_CURRENCY");

        // RFC 9457 shape (§3). title, detail and errors[].message are human-readable, not contract.
        // type may be absent: RFC 9457 treats a missing type as about:blank.
        SoftAssertions shape = new SoftAssertions();
        shape.assertThat(text(problem, "type")).as("type").isIn(null, "about:blank");
        shape.assertThat(problem.path("status").asInt()).as("status").isEqualTo(400);
        shape.assertThat(text(problem, "instance")).as("instance").isEqualTo("/accounts");
        shape.assertAll();

        List<JsonNode> errors = errors(problem);
        assertThat(errors).hasSize(3);

        // currency > other; ties broken by field name.
        assertThat(errors.get(0).get("code").asString()).isEqualTo("INVALID_CURRENCY");
        assertThat(errors.get(0).get("field").asString()).isEqualTo("currencies[0]");
        assertThat(errors.subList(1, 3)).extracting(e -> e.get("code").asString())
                .containsExactly("VALIDATION_FAILED", "VALIDATION_FAILED");
        assertThat(errors.subList(1, 3)).extracting(e -> e.get("field").asString())
                .containsExactly("country", "customerId");
    }

    @Test
    void reportsInvalidCurrencyFirstWhenCurrencyAndCountryFail() {
        String customerId = uniqueCustomerId();
        JsonNode problem = expectProblem(post("""
                {"customerId": "%s", "country": "EST", "currencies": [null, "GBP", "usd"]}
                """.formatted(customerId)), 400, "INVALID_CURRENCY");

        List<JsonNode> errors = errors(problem);
        List<String> codes = errors.stream().map(e -> e.get("code").asString()).toList();
        assertThat(codes).containsExactly("INVALID_CURRENCY", "INVALID_CURRENCY", "VALIDATION_FAILED");
        assertThat(errors.get(0).get("field").asString()).isEqualTo("currencies[0]");
        assertThat(errors.get(1).get("field").asString()).isEqualTo("currencies[2]");
        assertThat(errors.get(2).get("field").asString()).isEqualTo("country");
        assertNoOutboxRowMentions(customerId);
    }

    // ---------------------------------------------------------------- outbox (§5)

    @Test
    void writesAccountCreatedThenOneBalanceCreatedPerCurrencyToOutbox() {
        String customerId = uniqueCustomerId();
        JsonNode account = createAccount(customerId, "GB", "GBP", "EUR", "USD");
        String accountId = account.get("accountId").asString();

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT event_id::text AS event_id, routing_key, payload::text AS payload
                FROM outbox_event WHERE payload->>'accountId' = ? ORDER BY id
                """, accountId);

        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(r -> r.get("routing_key"))
                .containsExactly("account.created", "balance.created", "balance.created", "balance.created");

        Set<String> eventIds = new HashSet<>();
        List<String> balanceCurrencies = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            JsonNode envelope = JSON.readTree((String) row.get("payload"));
            assertThat(fieldNames(envelope))
                    .containsExactlyInAnyOrder("eventId", "eventType", "occurredAt", "accountId", "data");

            String eventId = envelope.get("eventId").asString();
            assertThat(UUID.fromString(eventId).toString()).isEqualTo(eventId);
            assertThat(eventId).isEqualTo(row.get("event_id"));
            eventIds.add(eventId);
            assertThat(envelope.get("eventType").asString()).isEqualTo(row.get("routing_key"));
            assertThat(Instant.parse(envelope.get("occurredAt").asString())).isNotNull();
            assertThat(envelope.get("accountId").asString()).isEqualTo(accountId);

            JsonNode data = envelope.get("data");
            if (i == 0) {
                assertThat(fieldNames(data)).containsExactlyInAnyOrder("accountId", "customerId", "country");
                assertThat(data.get("accountId").asString()).isEqualTo(accountId);
                assertThat(data.get("customerId").asString()).isEqualTo(customerId);
                assertThat(data.get("country").asString()).isEqualTo("GB");
            } else {
                assertThat(fieldNames(data)).containsExactlyInAnyOrder("accountId", "currency", "availableAmount");
                assertThat(data.get("accountId").asString()).isEqualTo(accountId);
                balanceCurrencies.add(data.get("currency").asString());
                JsonNode amount = data.get("availableAmount");
                assertThat(amount.isNumber()).as("availableAmount is a JSON number").isTrue();
                assertThat(amount.decimalValue()).isEqualByComparingTo(new BigDecimal("0.00"));
                assertThat(amount.decimalValue().scale()).isEqualTo(2);
            }
        }
        assertThat(eventIds).hasSize(4);
        // §5: balance.created events follow currency order, not request order.
        assertThat(balanceCurrencies).containsExactly("EUR", "GBP", "USD");
    }

    // ---------------------------------------------------------------- GET /accounts/{accountId} (§2)

    @Test
    void getsAccountWithZeroBalancesInCurrencyOrderMatchingCreateResponse() {
        String customerId = uniqueCustomerId();
        JsonNode created = createAccount(customerId, "LV", "USD", "EUR", "SEK");
        String accountId = created.get("accountId").asString();

        EntityExchangeResult<String> result = get(accountId);
        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(200);
        MediaType contentType = result.getResponseHeaders().getContentType();
        assertThat(contentType).isNotNull();
        assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_JSON))
                .as("Content-Type %s", contentType).isTrue();

        JsonNode body = JSON.readTree(result.getResponseBody());
        assertThat(body.get("accountId").asString()).isEqualTo(accountId);
        assertThat(body.get("customerId").asString()).isEqualTo(customerId);
        assertThat(body.get("country").asString()).isEqualTo("LV");

        JsonNode balances = body.get("balances");
        assertThat(balances.isArray()).isTrue();
        List<String> currencies = new ArrayList<>();
        for (JsonNode balance : balances) {
            currencies.add(balance.get("currency").asString());
            JsonNode amount = balance.get("availableAmount");
            assertThat(amount.isNumber()).as("availableAmount is a JSON number").isTrue();
            assertThat(amount.decimalValue()).isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(amount.decimalValue().scale()).isEqualTo(2);
        }
        assertThat(currencies).containsExactly("EUR", "SEK", "USD");

        assertThat(body).as("GET body equals POST response body").isEqualTo(created);
    }

    // ---------------------------------------------------------------- ACCOUNT_NOT_FOUND (§3)

    @Test
    void rejectsUnknownAccountIdWith404AccountNotFound() {
        String unknownId = UUID.randomUUID().toString();
        JsonNode problem = expectProblem(get(unknownId), 404, "ACCOUNT_NOT_FOUND");
        assertThat(text(problem, "instance")).isEqualTo("/accounts/" + unknownId);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-a-uuid",
            "123",
            "123e4567-e89b-12d3-a456-4266141740000",   // one character too many
            "123e4567-e89b-12d3-a456-42661417400",     // one character too few
            "1-2-3-4-5",                                // five dash-separated groups, too short
            "123e4567-e89b-12d3-a456-426614174000a",   // valid canonical UUID plus one trailing character
            "123e4567e89b12d3a456426614174000"})       // the 32 hex digits without dashes
    void rejectsMalformedAccountIdWith400AccountNotFound(String malformedId) {
        JsonNode problem = expectProblem(get(malformedId), 400, "ACCOUNT_NOT_FOUND");
        assertThat(text(problem, "instance")).isEqualTo("/accounts/" + malformedId);
    }

    @Test
    void rejectsBraceWrappedAccountIdWith400AccountNotFound() {
        // §2: braces make the ID malformed. The braces are percent-encoded so the request
        // reaches /accounts/{accountId}; instance is the request path as sent.
        String path = "/accounts/%7B" + UUID.randomUUID() + "%7D";
        EntityExchangeResult<String> result = client.get().uri(URI.create(path))
                .exchange()
                .returnResult(String.class);
        JsonNode problem = expectProblem(result, 400, "ACCOUNT_NOT_FOUND");
        assertThat(text(problem, "instance")).isEqualTo(path);
    }

    @Test
    void getsAccountByUppercasedAccountId() {
        String customerId = uniqueCustomerId();
        JsonNode created = createAccount(customerId, "SE", "SEK", "EUR");
        String accountId = created.get("accountId").asString();
        String upper = accountId.toUpperCase(Locale.ROOT);
        assertThat(upper).as("ID contains hex letters, so uppercasing changes it").isNotEqualTo(accountId);

        EntityExchangeResult<String> result = get(upper);
        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(200);

        JsonNode body = JSON.readTree(result.getResponseBody());
        assertThat(body.get("accountId").asString()).isEqualTo(accountId);
        assertThat(body.get("customerId").asString()).isEqualTo(customerId);
        assertThat(body.get("country").asString()).isEqualTo("SE");
        JsonNode balances = body.get("balances");
        assertThat(balances.size()).isEqualTo(2);
        List<String> currencies = new ArrayList<>();
        for (JsonNode balance : balances) {
            currencies.add(balance.get("currency").asString());
            JsonNode amount = balance.get("availableAmount");
            assertThat(amount.isNumber()).as("availableAmount is a JSON number").isTrue();
            assertThat(amount.decimalValue()).isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(amount.decimalValue().scale()).isEqualTo(2);
        }
        assertThat(currencies).containsExactly("EUR", "SEK");
        assertThat(body).as("GET by uppercased ID equals POST response body").isEqualTo(created);
    }

    // ---------------------------------------------------------------- outbox (§5)

    @Test
    void getAccountWritesNoOutboxRow() {
        JsonNode created = createAccount(uniqueCustomerId(), "EE", "EUR", "GBP");
        String accountId = created.get("accountId").asString();
        List<String> before = outboxRoutingKeys(accountId);
        assertThat(before).containsExactly("account.created", "balance.created", "balance.created");

        EntityExchangeResult<String> result = get(accountId);
        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(200);

        assertThat(outboxRoutingKeys(accountId)).isEqualTo(before);
    }

    // ---------------------------------------------------------------- helpers

    private EntityExchangeResult<String> get(String accountId) {
        return client.get().uri("/accounts/{accountId}", accountId)
                .exchange()
                .returnResult(String.class);
    }

    private List<String> outboxRoutingKeys(String accountId) {
        return jdbc.queryForList(
                "SELECT routing_key FROM outbox_event WHERE payload->>'accountId' = ? ORDER BY id",
                String.class, accountId);
    }

    private EntityExchangeResult<String> post(String json) {
        return client.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .exchange()
                .returnResult(String.class);
    }

    private JsonNode createAccount(String customerId, String country, String... currencies) {
        String list = String.join(", ", Stream.of(currencies).map(c -> "\"" + c + "\"").toList());
        EntityExchangeResult<String> result = post("""
                {"customerId": "%s", "country": "%s", "currencies": [%s]}
                """.formatted(customerId, country, list));
        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(201);
        return JSON.readTree(result.getResponseBody());
    }

    private JsonNode expectProblem(EntityExchangeResult<String> result, int status, String code) {
        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(status);
        MediaType contentType = result.getResponseHeaders().getContentType();
        assertThat(contentType).isNotNull();
        assertThat(contentType.isCompatibleWith(PROBLEM_JSON))
                .as("Content-Type %s", contentType).isTrue();
        JsonNode problem = JSON.readTree(result.getResponseBody());
        assertThat(problem.get("status").asInt()).isEqualTo(status);
        assertThat(problem.path("code").asString()).isEqualTo(code);
        return problem;
    }

    private static List<JsonNode> errors(JsonNode problem) {
        JsonNode errors = problem.get("errors");
        assertThat(errors).as("errors[] present").isNotNull();
        assertThat(errors.isArray()).isTrue();
        List<JsonNode> list = new ArrayList<>();
        errors.forEach(list::add);
        return list;
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static List<String> fieldNames(JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }

    private void assertNoOutboxRowMentions(String marker) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE payload::text LIKE ?",
                Integer.class, "%" + marker + "%");
        assertThat(count).isZero();
    }

    private void assertNoAccountCreatedWithBlankCustomerId() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event
                WHERE routing_key = 'account.created'
                  AND coalesce(trim(payload->'data'->>'customerId'), '') = ''
                """, Integer.class);
        assertThat(count).isZero();
    }

    private static String uniqueCustomerId() {
        return "C-" + UUID.randomUUID();
    }

    private static String randomSuffix(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length);
    }
}
