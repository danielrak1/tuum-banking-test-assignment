package com.danielrak.banking;

import static com.danielrak.banking.BankingApi.JSON;
import static com.danielrak.banking.BankingApi.assertErrors;
import static com.danielrak.banking.BankingApi.errors;
import static com.danielrak.banking.BankingApi.expectProblem;
import static com.danielrak.banking.BankingApi.fieldNames;
import static com.danielrak.banking.BankingApi.uniqueCustomerId;
import static com.danielrak.banking.BankingEvents.assertEnvelope;
import static com.danielrak.banking.BankingEvents.routingKeys;
import static org.assertj.core.api.Assertions.assertThat;

import com.danielrak.banking.BankingEvents.Event;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

/** Black-box contract tests for POST /accounts and GET /accounts/{accountId}: design.md §2 (API), §3 (errors), §5 (events). */
@IntegrationTest
class AccountApiIT {

    @Autowired
    RestTestClient client;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RabbitTemplate rabbitTemplate;

    BankingApi api;
    BankingEvents events;

    @BeforeEach
    void setUp() {
        api = new BankingApi(client);
        events = new BankingEvents(rabbitTemplate, api, jdbc);
    }

    // ---------------------------------------------------------------- success (§2)

    @Test
    void createsAccountWithLocationAndZeroBalancesOrderedByCurrency() {
        String customerId = uniqueCustomerId();
        EntityExchangeResult<String> result = api.postAccountRaw("""
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
        JsonNode problem = expectProblem(api.postAccountRaw("""
                {"customerId": "%s", "country": "EE", "currencies": %s}
                """.formatted(customerId, currencies)), 400, "INVALID_CURRENCY");

        List<JsonNode> errors = errors(problem);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("field").asString()).isEqualTo(field);
        assertThat(errors.get(0).get("code").asString()).isEqualTo("INVALID_CURRENCY");
        events.assertNoEventMentions(customerId);
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
        EntityExchangeResult<String> result = api.postAccountRaw("""
                {"customerId": "%s Pay \\ud83d\\ude00", "country": "EE", "currencies": ["EUR"]}
                """.formatted(prefix));
        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(201);
        JsonNode body = JSON.readTree(result.getResponseBody());
        String accountId = body.get("accountId").asString();
        assertThat(body.get("customerId").asString()).isEqualTo(customerId);

        JsonNode fetched = JSON.readTree(api.getAccountRaw(accountId).getResponseBody());
        assertThat(fetched.get("customerId").asString()).isEqualTo(customerId);

        assertThat(accountCreatedEvent(accountId).data().get("customerId").asString()).isEqualTo(customerId);
    }

    @Test
    void rejectsNumericCustomerIdWithValidationFailed() {
        JsonNode problem = expectProblem(api.postAccountRaw("""
                {"customerId": 12345, "country": "EE", "currencies": ["EUR"]}
                """), 400, "VALIDATION_FAILED");
        List<JsonNode> errors = errors(problem);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("field").asString()).isEqualTo("customerId");
        assertThat(errors.get(0).get("code").asString()).isEqualTo("VALIDATION_FAILED");
        events.fence();
        assertThat(events.eventsMatching(e -> "account.created".equals(e.eventType())
                && "12345".equals(e.data().path("customerId").asString())))
                .as("account.created events with customerId 12345").isEmpty();
    }

    @Test
    void rejectsNonStringCurrencyElementWithInvalidCurrencyAtItsIndex() {
        // §3 rule 4: a list element is mapped with the element rule (INVALID_CURRENCY) and its indexed path.
        String customerId = uniqueCustomerId();
        JsonNode problem = expectProblem(api.postAccountRaw("""
                {"customerId": "%s", "country": "EE", "currencies": ["EUR", 1]}
                """.formatted(customerId)), 400, "INVALID_CURRENCY");
        assertErrors(problem, "currencies[1]", "INVALID_CURRENCY");
        events.assertNoEventMentions(customerId);
    }

    @Test
    void rejectsStringCurrenciesWithValidationFailedOnTheListField() {
        // §3 rule 4: the list field itself gets VALIDATION_FAILED, not the element code.
        String customerId = uniqueCustomerId();
        JsonNode problem = expectProblem(api.postAccountRaw("""
                {"customerId": "%s", "country": "EE", "currencies": "EUR"}
                """.formatted(customerId)), 400, "VALIDATION_FAILED");
        assertErrors(problem, "currencies", "VALIDATION_FAILED");
        events.assertNoEventMentions(customerId);
    }

    @Test
    void rejectsNumericCountryWithValidationFailed() {
        String customerId = uniqueCustomerId();
        JsonNode problem = expectProblem(api.postAccountRaw("""
                {"customerId": "%s", "country": 42, "currencies": ["EUR"]}
                """.formatted(customerId)), 400, "VALIDATION_FAILED");
        assertErrors(problem, "country", "VALIDATION_FAILED");
        events.assertNoEventMentions(customerId);
    }

    @Test
    void rejectsSyntaxErrorInsideListWithValidationFailedAndNoErrors() {
        // §3 rule 4: a syntax error is a malformed document even inside a list: no errors[].
        String customerId = uniqueCustomerId();
        JsonNode problem = expectProblem(api.postAccountRaw("""
                {"customerId": "%s", "country": "EE", "currencies": ["EUR" "USD"]}
                """.formatted(customerId)), 400, "VALIDATION_FAILED");
        assertThat(problem.has("errors")).as("errors[] in %s", problem).isFalse();
        events.assertNoEventMentions(customerId);
    }

    @Test
    void acceptsNonAsciiCustomerIdAndStoresItUnchanged() {
        // §2: the free-text rule rejects only control characters; other Unicode is valid.
        String customerId = "Jüri Õun " + randomSuffix(8);
        JsonNode body = createAccount(customerId, "EE", "EUR");
        String accountId = body.get("accountId").asString();
        assertThat(body.get("customerId").asString()).isEqualTo(customerId);

        JsonNode fetched = JSON.readTree(api.getAccountRaw(accountId).getResponseBody());
        assertThat(fetched.get("customerId").asString()).isEqualTo(customerId);

        assertThat(accountCreatedEvent(accountId).data().get("customerId").asString()).isEqualTo(customerId);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validationFailures")
    void rejectsInvalidFieldWithValidationFailed(String label, String json, String field, String marker) {
        JsonNode problem = expectProblem(api.postAccountRaw(json), 400, "VALIDATION_FAILED");

        List<JsonNode> errors = errors(problem);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("field").asString()).isEqualTo(field);
        assertThat(errors.get(0).get("code").asString()).isEqualTo("VALIDATION_FAILED");
        if (marker != null) {
            events.assertNoEventMentions(marker);
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
        expectProblem(api.postAccountRaw(template.formatted(marker)), 400, "VALIDATION_FAILED");
        events.assertNoEventMentions(marker);
    }

    // ---------------------------------------------------------------- several failures (§3 rule 3)

    @Test
    void reportsAllFailingFieldsWithCurrencyFirstAndFullProblemShape() {
        JsonNode problem = expectProblem(api.postAccountRaw("""
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
        JsonNode problem = expectProblem(api.postAccountRaw("""
                {"customerId": "%s", "country": "EST", "currencies": [null, "GBP", "usd"]}
                """.formatted(customerId)), 400, "INVALID_CURRENCY");

        List<JsonNode> errors = errors(problem);
        List<String> codes = errors.stream().map(e -> e.get("code").asString()).toList();
        assertThat(codes).containsExactly("INVALID_CURRENCY", "INVALID_CURRENCY", "VALIDATION_FAILED");
        assertThat(errors.get(0).get("field").asString()).isEqualTo("currencies[0]");
        assertThat(errors.get(1).get("field").asString()).isEqualTo("currencies[2]");
        assertThat(errors.get(2).get("field").asString()).isEqualTo("country");
        events.assertNoEventMentions(customerId);
    }

    // ---------------------------------------------------------------- events (§5)

    @Test
    void publishesAccountCreatedThenOneBalanceCreatedPerCurrency() {
        String customerId = uniqueCustomerId();
        JsonNode account = createAccount(customerId, "GB", "GBP", "EUR", "USD");
        String accountId = account.get("accountId").asString();

        List<Event> published = events.awaitEvents(accountId, 4);

        assertThat(published).hasSize(4);
        assertThat(routingKeys(published))
                .containsExactly("account.created", "balance.created", "balance.created", "balance.created");

        Set<String> eventIds = new HashSet<>();
        List<String> balanceCurrencies = new ArrayList<>();
        for (int i = 0; i < published.size(); i++) {
            Event event = published.get(i);
            assertEnvelope(event, accountId);
            eventIds.add(event.eventId());

            JsonNode data = event.data();
            if (i == 0) {
                assertThat(fieldNames(data)).as("§5 data fields, in order")
                        .containsExactly("accountId", "customerId", "country");
                assertThat(data.get("accountId").asString()).isEqualTo(accountId);
                assertThat(data.get("customerId").asString()).isEqualTo(customerId);
                assertThat(data.get("country").asString()).isEqualTo("GB");
            } else {
                assertThat(fieldNames(data)).as("§5 data fields, in order")
                        .containsExactly("accountId", "currency", "availableAmount");
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

        EntityExchangeResult<String> result = api.getAccountRaw(accountId);
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
        JsonNode problem = expectProblem(api.getAccountRaw(unknownId), 404, "ACCOUNT_NOT_FOUND");
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
        JsonNode problem = expectProblem(api.getAccountRaw(malformedId), 400, "ACCOUNT_NOT_FOUND");
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

        EntityExchangeResult<String> result = api.getAccountRaw(upper);
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

    // ---------------------------------------------------------------- events (§5)

    @Test
    void getAccountPublishesNoEvent() {
        JsonNode created = createAccount(uniqueCustomerId(), "EE", "EUR", "GBP");
        String accountId = created.get("accountId").asString();
        List<Event> before = events.awaitEvents(accountId, 3);
        assertThat(routingKeys(before)).containsExactly("account.created", "balance.created", "balance.created");

        EntityExchangeResult<String> result = api.getAccountRaw(accountId);
        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(200);

        events.fence();
        assertThat(events.events(accountId)).as("no event after GET").isEqualTo(before);
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode createAccount(String customerId, String country, String... currencies) {
        String list = String.join(", ", Stream.of(currencies).map(c -> "\"" + c + "\"").toList());
        EntityExchangeResult<String> result = api.postAccountRaw("""
                {"customerId": "%s", "country": "%s", "currencies": [%s]}
                """.formatted(customerId, country, list));
        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(201);
        return JSON.readTree(result.getResponseBody());
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asString();
    }

    /** The account's {@code account.created} event (§5), once published. */
    private Event accountCreatedEvent(String accountId) {
        List<Event> published = events.awaitEvents(accountId, 1);
        Event first = published.getFirst();
        assertThat(first.eventType()).as("first event of %s", accountId).isEqualTo("account.created");
        assertEnvelope(first, accountId);
        return first;
    }

    /** After the fence, no {@code account.created} event (from any test) has a blank or missing customerId. */
    private void assertNoAccountCreatedWithBlankCustomerId() {
        events.fence();
        assertThat(events.eventsMatching(e -> "account.created".equals(e.eventType())
                && blankOrMissing(e.data() == null ? null : e.data().get("customerId"))))
                .as("account.created events with a blank customerId").isEmpty();
    }

    private static boolean blankOrMissing(JsonNode value) {
        return value == null || value.isNull() || (value.isString() && value.asString().isBlank());
    }

    private static String randomSuffix(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length);
    }
}
