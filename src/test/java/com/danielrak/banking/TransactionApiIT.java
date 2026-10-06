package com.danielrak.banking;

import static com.danielrak.banking.BankingApi.JSON;
import static com.danielrak.banking.BankingApi.assertErrors;
import static com.danielrak.banking.BankingApi.assertJsonContentType;
import static com.danielrak.banking.BankingApi.expectProblem;
import static com.danielrak.banking.BankingApi.fieldNames;
import static com.danielrak.banking.BankingEvents.assertEnvelope;
import static com.danielrak.banking.BankingEvents.routingKeys;
import static org.assertj.core.api.Assertions.assertThat;

import com.danielrak.banking.BankingEvents.Event;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

/**
 * Black-box contract tests for POST and GET /accounts/{accountId}/transactions:
 * design.md §2 (API), §3 (errors), §5 (events, read from the banking.events.all queue).
 */
@IntegrationTest
class TransactionApiIT {

    /** Marker for "leave this field out of the JSON body". */
    private static final String ABSENT = null;

    private static final List<String> TRANSACTION_FIELDS = List.of(
            "accountId", "transactionId", "amount", "currency", "direction", "description", "balanceAfter");

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

    // ================================================================ POST success (§2)

    @Test
    void postsInThenOutAndReturnsTransactionWithRunningBalanceAfter() {
        String accountId = api.createAccount("EUR", "USD");

        EntityExchangeResult<String> in = api.postTransaction(accountId, "100.00", "EUR", "IN", "Salary");
        assertThat(in.getStatus().value()).as(in.getResponseBody()).isEqualTo(201);
        assertJsonContentType(in);
        JsonNode inBody = JSON.readTree(in.getResponseBody());
        assertTransaction(inBody, accountId, "100.00", "EUR", "IN", "Salary", "100.00");

        EntityExchangeResult<String> out = api.postTransaction(accountId, "30.50", "EUR", "OUT", "Groceries");
        assertThat(out.getStatus().value()).as(out.getResponseBody()).isEqualTo(201);
        JsonNode outBody = JSON.readTree(out.getResponseBody());
        assertTransaction(outBody, accountId, "30.50", "EUR", "OUT", "Groceries", "69.50");

        assertThat(outBody.get("transactionId").asString())
                .isNotEqualTo(inBody.get("transactionId").asString());

        JsonNode account = api.getAccount(accountId);
        Map<String, BigDecimal> balances = balancesOf(account);
        assertThat(balances.keySet()).containsExactly("EUR", "USD");
        assertThat(balances.get("EUR")).isEqualByComparingTo(new BigDecimal("69.50"));
        assertThat(balances.get("EUR").scale()).isEqualTo(2);
        assertThat(balances.get("USD")).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(balances.get("USD").scale()).isEqualTo(2);
    }

    @Test
    void acceptsAmountWithTrailingZeroThirdDecimalAndReturnsItAtScale2() {
        String accountId = api.createAccount("EUR");
        JsonNode body = api.transact(accountId, "10.500", "EUR", "IN", "Trailing zero");
        assertTransaction(body, accountId, "10.50", "EUR", "IN", "Trailing zero", "10.50");
        BigDecimal stored = api.balance(accountId, "EUR");
        assertThat(stored).isEqualByComparingTo(new BigDecimal("10.50"));
        assertThat(stored.scale()).isEqualTo(2);
    }

    static Stream<Arguments> normalisedAmounts() {
        // §2: trailing zeros don't count, and the response always has scale 2.
        return Stream.of(
                Arguments.of("10.5000000", "10.50"),
                Arguments.of("1e2", "100.00"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("normalisedAmounts")
    void acceptsAmountWithTrailingZerosOrExponentNormalisedToScale2(String sent, String expected) {
        String accountId = api.createAccount("EUR");
        int before = events.awaitEvents(accountId, 2).size();

        JsonNode body = api.transact(accountId, sent, "EUR", "IN", "Normalised " + sent);
        assertTransaction(body, accountId, expected, "EUR", "IN", "Normalised " + sent, expected);

        BigDecimal stored = api.balance(accountId, "EUR");
        assertThat(stored).isEqualByComparingTo(new BigDecimal(expected));
        assertThat(stored.scale()).isEqualTo(2);

        List<JsonNode> list = api.listTransactions(accountId);
        assertThat(list).hasSize(1);
        assertTransaction(list.get(0), accountId, expected, "EUR", "IN", "Normalised " + sent, expected);

        assertTransactionEvents(accountId, before, body);
    }

    @Test
    void acceptsAmountWith17IntegerDigitsAnd2Decimals() {
        // §2: at most 17 integer digits, at most 2 decimals; this is the largest allowed amount.
        String accountId = api.createAccount("GBP");
        JsonNode body = api.transact(accountId, "99999999999999999.99", "GBP", "IN", "Max amount");
        assertTransaction(body, accountId, "99999999999999999.99", "GBP", "IN", "Max amount",
                "99999999999999999.99");
    }

    @Test
    void returnsNoLocationHeaderOnCreatedTransaction() {
        String accountId = api.createAccount("EUR");
        EntityExchangeResult<String> result = api.postTransaction(accountId, "1.00", "EUR", "IN", "No location");
        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(201);
        assertThat(result.getResponseHeaders().containsHeader(HttpHeaders.LOCATION))
                .as("Location header: %s", result.getResponseHeaders().get(HttpHeaders.LOCATION))
                .isFalse();
    }

    @Test
    void acceptsDescriptionOfExactly255Characters() {
        String accountId = api.createAccount("EUR");
        String description = "d".repeat(255);
        JsonNode body = api.transact(accountId, "1.00", "EUR", "IN", description);
        assertTransaction(body, accountId, "1.00", "EUR", "IN", description, "1.00");
    }

    @Test
    void acceptsUppercasedAccountId() {
        // §2: the path ID is case-insensitive.
        String accountId = api.createAccount("SEK");
        String upper = accountId.toUpperCase(Locale.ROOT);
        assertThat(upper).as("ID contains hex letters").isNotEqualTo(accountId);
        JsonNode body = api.transact(upper, "5.00", "SEK", "IN", "Upper");
        assertTransaction(body, accountId, "5.00", "SEK", "IN", "Upper", "5.00");
    }

    // ================================================================ POST 400 per field (§3)

    static Stream<Arguments> singleFieldFailures() {
        return Stream.of(
                // currency
                Arguments.of("currency missing", "10.00", ABSENT, "\"IN\"", "\"Pay\"", "currency", "INVALID_CURRENCY"),
                Arguments.of("currency null", "10.00", "null", "\"IN\"", "\"Pay\"", "currency", "INVALID_CURRENCY"),
                Arguments.of("currency JPY", "10.00", "\"JPY\"", "\"IN\"", "\"Pay\"", "currency", "INVALID_CURRENCY"),
                Arguments.of("currency eur", "10.00", "\"eur\"", "\"IN\"", "\"Pay\"", "currency", "INVALID_CURRENCY"),
                // direction
                Arguments.of("direction missing", "10.00", "\"EUR\"", ABSENT, "\"Pay\"", "direction", "INVALID_DIRECTION"),
                Arguments.of("direction null", "10.00", "\"EUR\"", "null", "\"Pay\"", "direction", "INVALID_DIRECTION"),
                Arguments.of("direction in", "10.00", "\"EUR\"", "\"in\"", "\"Pay\"", "direction", "INVALID_DIRECTION"),
                Arguments.of("direction SIDEWAYS", "10.00", "\"EUR\"", "\"SIDEWAYS\"", "\"Pay\"", "direction", "INVALID_DIRECTION"),
                // amount
                Arguments.of("amount missing", ABSENT, "\"EUR\"", "\"IN\"", "\"Pay\"", "amount", "INVALID_AMOUNT"),
                Arguments.of("amount null", "null", "\"EUR\"", "\"IN\"", "\"Pay\"", "amount", "INVALID_AMOUNT"),
                Arguments.of("amount 0", "0", "\"EUR\"", "\"IN\"", "\"Pay\"", "amount", "INVALID_AMOUNT"),
                Arguments.of("amount 0.00", "0.00", "\"EUR\"", "\"IN\"", "\"Pay\"", "amount", "INVALID_AMOUNT"),
                Arguments.of("amount -1", "-1", "\"EUR\"", "\"IN\"", "\"Pay\"", "amount", "INVALID_AMOUNT"),
                Arguments.of("amount 10.555", "10.555", "\"EUR\"", "\"IN\"", "\"Pay\"", "amount", "INVALID_AMOUNT"),
                Arguments.of("amount 1e18", "1e18", "\"EUR\"", "\"IN\"", "\"Pay\"", "amount", "INVALID_AMOUNT"),
                Arguments.of("amount 18 integer digits", "100000000000000000.00", "\"EUR\"", "\"IN\"", "\"Pay\"",
                        "amount", "INVALID_AMOUNT"),
                // amount: trailing zeros don't count (§2), so 0.000 is zero and 0.001 has 3 decimals
                Arguments.of("amount 0.000", "0.000", "\"EUR\"", "\"IN\"", "\"Pay\"", "amount", "INVALID_AMOUNT"),
                Arguments.of("amount 0.001", "0.001", "\"EUR\"", "\"IN\"", "\"Pay\"", "amount", "INVALID_AMOUNT"),
                // amount: extreme exponents, far more than 17 integer digits; a 400, never a 500
                Arguments.of("amount 123e2147483645", "123e2147483645", "\"EUR\"", "\"IN\"", "\"Pay\"",
                        "amount", "INVALID_AMOUNT"),
                Arguments.of("amount 100e2147483647", "100e2147483647", "\"EUR\"", "\"IN\"", "\"Pay\"",
                        "amount", "INVALID_AMOUNT"),
                // description: missing or blank
                Arguments.of("description missing", "10.00", "\"EUR\"", "\"IN\"", ABSENT, "description", "DESCRIPTION_MISSING"),
                Arguments.of("description null", "10.00", "\"EUR\"", "\"IN\"", "null", "description", "DESCRIPTION_MISSING"),
                Arguments.of("description empty", "10.00", "\"EUR\"", "\"IN\"", "\"\"", "description", "DESCRIPTION_MISSING"),
                Arguments.of("description spaces", "10.00", "\"EUR\"", "\"IN\"", "\"   \"", "description", "DESCRIPTION_MISSING"),
                // description: too long or control characters (sent as JSON escapes)
                Arguments.of("description 256 chars", "10.00", "\"EUR\"", "\"IN\"", "\"" + "d".repeat(256) + "\"",
                        "description", "VALIDATION_FAILED"),
                Arguments.of("description U+0001", "10.00", "\"EUR\"", "\"IN\"", "\"a\\u0001b\"",
                        "description", "VALIDATION_FAILED"),
                Arguments.of("description tab", "10.00", "\"EUR\"", "\"IN\"", "\"Rent\\tMay\"",
                        "description", "VALIDATION_FAILED"),
                Arguments.of("description newline", "10.00", "\"EUR\"", "\"IN\"", "\"Rent\\nMay\"",
                        "description", "VALIDATION_FAILED"),
                // description: C1 controls, line/paragraph separators, unpaired surrogates (§2 free text)
                Arguments.of("description NEL U+0085", "10.00", "\"EUR\"", "\"IN\"", "\"a\\u0085b\"",
                        "description", "VALIDATION_FAILED"),
                Arguments.of("description U+009F", "10.00", "\"EUR\"", "\"IN\"", "\"a\\u009Fb\"",
                        "description", "VALIDATION_FAILED"),
                Arguments.of("description U+2028", "10.00", "\"EUR\"", "\"IN\"", "\"a\\u2028b\"",
                        "description", "VALIDATION_FAILED"),
                Arguments.of("description U+2029", "10.00", "\"EUR\"", "\"IN\"", "\"a\\u2029b\"",
                        "description", "VALIDATION_FAILED"),
                Arguments.of("description unpaired high surrogate", "10.00", "\"EUR\"", "\"IN\"", "\"x\\ud800y\"",
                        "description", "VALIDATION_FAILED"),
                Arguments.of("description unpaired low surrogate", "10.00", "\"EUR\"", "\"IN\"", "\"x\\udc00y\"",
                        "description", "VALIDATION_FAILED"),
                Arguments.of("description reversed surrogate pair", "10.00", "\"EUR\"", "\"IN\"",
                        "\"x\\ude00\\ud83dy\"", "description", "VALIDATION_FAILED"),
                // description: Unicode spaces count as blank (§2 description row, §3 description row)
                Arguments.of("description U+00A0", "10.00", "\"EUR\"", "\"IN\"", "\"\\u00A0\"",
                        "description", "DESCRIPTION_MISSING"),
                Arguments.of("description U+2003", "10.00", "\"EUR\"", "\"IN\"", "\"\\u2003\"",
                        "description", "DESCRIPTION_MISSING"),
                Arguments.of("description U+2007 U+202F", "10.00", "\"EUR\"", "\"IN\"", "\"\\u2007\\u202F\"",
                        "description", "DESCRIPTION_MISSING"),
                Arguments.of("description U+3000", "10.00", "\"EUR\"", "\"IN\"", "\"\\u3000\"",
                        "description", "DESCRIPTION_MISSING"),
                Arguments.of("description U+00A0 space U+3000", "10.00", "\"EUR\"", "\"IN\"", "\"\\u00A0 \\u3000\"",
                        "description", "DESCRIPTION_MISSING"));
    }

    static Stream<Arguments> acceptedFreeTextDescriptions() {
        return Stream.of(
                Arguments.of("surrogate pair (emoji)", "Pay \\ud83d\\ude00", "Pay \uD83D\uDE00"),
                Arguments.of("inner U+00A0", "a\\u00A0b", "a\u00A0b"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedFreeTextDescriptions")
    void acceptsFreeTextDescriptionAndStoresItUnchanged(String label, String escaped, String expected) {
        // §2: free text is stored exactly as sent; the JSON escapes put the exact UTF-16 on the wire.
        String accountId = api.createAccount("EUR");
        int before = events.awaitEvents(accountId, 2).size();

        EntityExchangeResult<String> result = api.postTransactionRaw(accountId,
                body("10.00", "\"EUR\"", "\"IN\"", "\"" + escaped + "\""));
        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(201);
        JsonNode tx = JSON.readTree(result.getResponseBody());
        assertTransaction(tx, accountId, "10.00", "EUR", "IN", expected, "10.00");

        List<JsonNode> list = api.listTransactions(accountId);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("description").asString()).isEqualTo(expected);

        List<Event> added = assertTransactionEvents(accountId, before, tx);
        assertThat(added.getFirst().data().get("description").asString()).isEqualTo(expected);
    }

    // §2: lengths count Unicode code points (an emoji is 1), not UTF-16 units.
    private static final String EMOJI = Character.toString(0x1F600);

    @Test
    void acceptsDescriptionOf255EmojiAndStoresItUnchanged() {
        String description = EMOJI.repeat(255);
        assertThat(description.codePointCount(0, description.length())).isEqualTo(255);
        assertThat(description).as("510 UTF-16 units").hasSize(510);
        assertAcceptedDescriptionStoredUnchanged(description);
    }

    @Test
    void acceptsDescriptionOf254AsciiPlusOneEmojiAs255CodePoints() {
        // 255 code points, 256 UTF-16 units: a @Size(max = 255) check would reject it.
        String description = "d".repeat(254) + EMOJI;
        assertThat(description.codePointCount(0, description.length())).isEqualTo(255);
        assertThat(description).as("256 UTF-16 units").hasSize(256);
        assertAcceptedDescriptionStoredUnchanged(description);
    }

    @Test
    void rejectsDescriptionOf256EmojiWithValidationFailedAndChangesNothing() {
        String accountId = api.createAccount("EUR");
        int before = events.awaitEvents(accountId, 2).size();

        JsonNode problem = expectProblem(
                api.postTransaction(accountId, "10.00", "EUR", "IN", EMOJI.repeat(256)),
                400, "VALIDATION_FAILED");
        assertErrors(problem, "description", "VALIDATION_FAILED");
        assertNothingChanged(accountId, before, 0, "EUR", "0.00");
    }

    private void assertAcceptedDescriptionStoredUnchanged(String description) {
        String accountId = api.createAccount("EUR");
        int before = events.awaitEvents(accountId, 2).size();

        EntityExchangeResult<String> result = api.postTransaction(accountId, "10.00", "EUR", "IN", description);
        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(201);
        JsonNode tx = JSON.readTree(result.getResponseBody());
        assertTransaction(tx, accountId, "10.00", "EUR", "IN", description, "10.00");

        List<JsonNode> list = api.listTransactions(accountId);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("description").asString()).isEqualTo(description);
        assertThat(api.balance(accountId, "EUR")).isEqualByComparingTo(new BigDecimal("10.00"));

        List<Event> added = assertTransactionEvents(accountId, before, tx);
        assertThat(added.getFirst().data().get("description").asString()).isEqualTo(description);
    }

    @Test
    void rejectsDuplicateAmountKeyWithValidationFailed() {
        // §3 rule 4: a duplicate key makes the document malformed; it doesn't get the field's code.
        String accountId = api.createAccount("EUR");
        int before = events.awaitEvents(accountId, 2).size();
        JsonNode problem = expectProblem(api.postTransactionRaw(accountId, """
                {"amount": 1.00, "currency": "EUR", "direction": "IN", "description": "Dup", "amount": 5000.00}
                """), 400, "VALIDATION_FAILED");
        assertThat(problem.has("errors")).as("errors[] in %s", problem).isFalse();
        assertNothingChanged(accountId, before, 0, "EUR", "0.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"42", "true"})
    void rejectsNonStringDescriptionWithDescriptionMissing(String description) {
        String accountId = api.createAccount("EUR");
        int before = events.awaitEvents(accountId, 2).size();
        JsonNode problem = expectProblem(
                api.postTransactionRaw(accountId, body("10.00", "\"EUR\"", "\"IN\"", description)),
                400, "DESCRIPTION_MISSING");
        assertErrors(problem, "description", "DESCRIPTION_MISSING");
        assertNothingChanged(accountId, before, 0, "EUR", "0.00");
    }

    @ParameterizedTest(name = "{0} -> {6}")
    @MethodSource("singleFieldFailures")
    void rejectsInvalidFieldWith400AndFieldCode(String label, String amount, String currency, String direction,
                                                String description, String field, String code) {
        String accountId = api.createAccount("EUR");
        int before = events.awaitEvents(accountId, 2).size();

        JsonNode problem = expectProblem(
                api.postTransactionRaw(accountId, body(amount, currency, direction, description)), 400, code);
        assertErrors(problem, field, code);

        assertNothingChanged(accountId, before, 0, "EUR", "0.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"amount\": 10.00, \"currency\": \"EUR\", \"direction\": \"IN\", \"description\": \"x\"",
            "{\"amount\": 10.00 \"currency\": \"EUR\"}",
            "not json"})
    void rejectsMalformedJsonWithValidationFailed(String json) {
        String accountId = api.createAccount("EUR");
        int before = events.awaitEvents(accountId, 2).size();

        expectProblem(api.postTransactionRaw(accountId, json), 400, "VALIDATION_FAILED");

        assertNothingChanged(accountId, before, 0, "EUR", "0.00");
    }

    @Test
    void rejectsStringAmountWithInvalidAmount() {
        String accountId = api.createAccount("EUR");
        JsonNode problem = expectProblem(api.postTransactionRaw(accountId, """
                {"amount": "10.50", "currency": "EUR", "direction": "IN", "description": "String amount"}
                """), 400, "INVALID_AMOUNT");
        assertErrors(problem, "amount", "INVALID_AMOUNT");
    }

    @Test
    void rejectsUnparseableAmountWithInvalidAmount() {
        String accountId = api.createAccount("EUR");
        expectProblem(api.postTransactionRaw(accountId, """
                {"amount": "abc", "currency": "EUR", "direction": "IN", "description": "Bad amount"}
                """), 400, "INVALID_AMOUNT");
    }

    // ================================================================ POST parse errors (§3 rule 4)

    @Test
    void rejectsNumericCurrencyWithInvalidCurrency() {
        String accountId = api.createAccount("EUR");
        int before = events.awaitEvents(accountId, 2).size();
        JsonNode problem = expectProblem(
                api.postTransactionRaw(accountId, body("10.00", "1", "\"IN\"", "\"Pay\"")),
                400, "INVALID_CURRENCY");
        assertErrors(problem, "currency", "INVALID_CURRENCY");
        assertNothingChanged(accountId, before, 0, "EUR", "0.00");
    }

    @Test
    void rejectsBooleanDirectionWithInvalidDirection() {
        String accountId = api.createAccount("EUR");
        int before = events.awaitEvents(accountId, 2).size();
        JsonNode problem = expectProblem(
                api.postTransactionRaw(accountId, body("10.00", "\"EUR\"", "true", "\"Pay\"")),
                400, "INVALID_DIRECTION");
        assertErrors(problem, "direction", "INVALID_DIRECTION");
        assertNothingChanged(accountId, before, 0, "EUR", "0.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "{}", "\"\""})
    void rejectsNonNumberAmountWithInvalidAmount(String amount) {
        // §3 rule 4: a wrong JSON type (boolean, object, empty string) is a parse error mapped to the amount.
        String accountId = api.createAccount("EUR");
        int before = events.awaitEvents(accountId, 2).size();
        JsonNode problem = expectProblem(
                api.postTransactionRaw(accountId, body(amount, "\"EUR\"", "\"IN\"", "\"Pay\"")),
                400, "INVALID_AMOUNT");
        assertErrors(problem, "amount", "INVALID_AMOUNT");
        assertNothingChanged(accountId, before, 0, "EUR", "0.00");
    }

    @Test
    void reportsOnlyTheAmountParseErrorWhenAnotherFieldIsAlsoInvalid() {
        // §3 rule 4: parsing stops at the first unreadable value, so the bad currency isn't reported.
        String accountId = api.createAccount("EUR");
        int before = events.awaitEvents(accountId, 2).size();
        JsonNode problem = expectProblem(
                api.postTransactionRaw(accountId, body("\"abc\"", "\"XXX\"", "\"IN\"", "\"Pay\"")),
                400, "INVALID_AMOUNT");
        assertErrors(problem, "amount", "INVALID_AMOUNT");
        assertNothingChanged(accountId, before, 0, "EUR", "0.00");
    }

    @Test
    void rejectsDuplicateDescriptionKeyWithValidationFailedAndNoErrors() {
        // §3 rule 4: a duplicate key (any field) makes the document malformed: no errors[].
        String accountId = api.createAccount("EUR");
        int before = events.awaitEvents(accountId, 2).size();
        JsonNode problem = expectProblem(api.postTransactionRaw(accountId, """
                {"amount": 10.00, "currency": "EUR", "direction": "IN", "description": "First", "description": "Second"}
                """), 400, "VALIDATION_FAILED");
        assertThat(problem.has("errors")).as("errors[] in %s", problem).isFalse();
        assertNothingChanged(accountId, before, 0, "EUR", "0.00");
    }

    @Test
    void rejectsNonObjectBodyWithValidationFailedAndNoErrors() {
        // §3 rule 4: a body that isn't a JSON object is not a well-formed request document.
        String accountId = api.createAccount("EUR");
        int before = events.awaitEvents(accountId, 2).size();
        JsonNode problem = expectProblem(api.postTransactionRaw(accountId, "[]"), 400, "VALIDATION_FAILED");
        assertThat(problem.has("errors")).as("errors[] in %s", problem).isFalse();
        assertNothingChanged(accountId, before, 0, "EUR", "0.00");
    }

    @Test
    void acceptsIntegerJsonNumberAmountAndReturnsItAtScale2() {
        // §2: the amount is a JSON number; an integer such as 10 is still valid.
        String accountId = api.createAccount("EUR");
        int before = events.awaitEvents(accountId, 2).size();

        EntityExchangeResult<String> result = api.postTransactionRaw(accountId,
                body("10", "\"EUR\"", "\"IN\"", "\"Integer amount\""));
        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(201);
        JsonNode tx = JSON.readTree(result.getResponseBody());
        assertTransaction(tx, accountId, "10.00", "EUR", "IN", "Integer amount", "10.00");

        assertTransactionEvents(accountId, before, tx);
    }

    // ================================================================ POST several failures (§3 rule 3)

    @Test
    void reportsAllFourFailingFieldsWithInvalidCurrencyOnTop() {
        String accountId = api.createAccount("EUR");
        int before = events.awaitEvents(accountId, 2).size();

        JsonNode problem = expectProblem(api.postTransactionRaw(accountId, """
                {"amount": -1, "currency": "JPY", "direction": "in", "description": ""}
                """), 400, "INVALID_CURRENCY");
        assertErrors(problem,
                "currency", "INVALID_CURRENCY",
                "direction", "INVALID_DIRECTION",
                "amount", "INVALID_AMOUNT",
                "description", "DESCRIPTION_MISSING");

        assertNothingChanged(accountId, before, 0, "EUR", "0.00");
    }

    @Test
    void reportsInvalidDirectionOnTopWhenCurrencyIsValid() {
        String accountId = api.createAccount("EUR");
        JsonNode problem = expectProblem(api.postTransactionRaw(accountId, """
                {"amount": 0, "currency": "EUR", "direction": "SIDEWAYS", "description": "   "}
                """), 400, "INVALID_DIRECTION");
        assertErrors(problem,
                "direction", "INVALID_DIRECTION",
                "amount", "INVALID_AMOUNT",
                "description", "DESCRIPTION_MISSING");
    }

    @Test
    void reportsInvalidAmountOnTopWhenOnlyAmountAndDescriptionFail() {
        String accountId = api.createAccount("EUR");
        JsonNode problem = expectProblem(api.postTransactionRaw(accountId, """
                {"amount": 10.555, "currency": "EUR", "direction": "OUT"}
                """), 400, "INVALID_AMOUNT");
        assertErrors(problem,
                "amount", "INVALID_AMOUNT",
                "description", "DESCRIPTION_MISSING");
    }

    // ================================================================ POST 422 (§3)

    @Test
    void rejectsSupportedCurrencyNotHeldWith422InvalidCurrency() {
        String accountId = api.createAccount("EUR");
        int before = events.awaitEvents(accountId, 2).size();

        expectProblem(api.postTransaction(accountId, "10.00", "USD", "IN", "Not held"), 422, "INVALID_CURRENCY");

        assertNothingChanged(accountId, before, 0, "EUR", "0.00");
        assertThat(balancesOf(api.getAccount(accountId)).keySet())
                .as("no balance is auto-opened").containsExactly("EUR");
    }

    @Test
    void rejectsOutOverBalanceWithInsufficientFundsAndLeavesStateUnchanged() {
        String accountId = api.createAccount("EUR");
        JsonNode opening = api.transact(accountId, "100.00", "EUR", "IN", "Opening");
        int before = events.awaitEvents(accountId, 4).size();

        expectProblem(api.postTransaction(accountId, "100.01", "EUR", "OUT", "Too much"), 422, "INSUFFICIENT_FUNDS");

        events.fence();
        assertThat(events.events(accountId)).as("no phantom events").hasSize(before);
        BigDecimal stored = api.balance(accountId, "EUR");
        assertThat(stored).isEqualByComparingTo(new BigDecimal("100.00"));
        List<JsonNode> list = api.listTransactions(accountId);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("transactionId").asString()).isEqualTo(opening.get("transactionId").asString());
    }

    @Test
    void acceptsOutOfExactlyTheBalanceLeavingZero() {
        String accountId = api.createAccount("EUR");
        api.transact(accountId, "42.42", "EUR", "IN", "Opening");
        JsonNode out = api.transact(accountId, "42.42", "EUR", "OUT", "All of it");
        assertTransaction(out, accountId, "42.42", "EUR", "OUT", "All of it", "0.00");
        assertThat(api.balance(accountId, "EUR")).isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void rejectsOutOnZeroBalanceWithInsufficientFunds() {
        String accountId = api.createAccount("EUR");
        int before = events.awaitEvents(accountId, 2).size();

        expectProblem(api.postTransaction(accountId, "0.01", "EUR", "OUT", "Nothing there"), 422, "INSUFFICIENT_FUNDS");

        assertNothingChanged(accountId, before, 0, "EUR", "0.00");
    }

    // ================================================================ POST path ID (§2, §3)

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "1-2-3-4-5", "123e4567e89b12d3a456426614174000"})
    void rejectsMalformedAccountIdWith400AccountMissing(String malformedId) {
        expectProblem(api.postTransaction(malformedId, "10.00", "EUR", "IN", "Malformed"), 400, "ACCOUNT_MISSING");
    }

    @Test
    void rejectsUnknownAccountIdWith404AccountMissingAndWritesNoEvent() {
        String unknownId = UUID.randomUUID().toString();
        expectProblem(api.postTransaction(unknownId, "10.00", "EUR", "IN", "Unknown"), 404, "ACCOUNT_MISSING");
        events.assertNoEventMentions(unknownId);
    }

    // ================================================================ POST order of checks (§3 rule 1)

    static Stream<Arguments> bodiesBehindMalformedAccountId() {
        return Stream.of(
                Arguments.of("every field invalid",
                        "{\"amount\": -1, \"currency\": \"JPY\", \"direction\": \"in\", \"description\": \"\"}"),
                Arguments.of("malformed JSON",
                        "{\"amount\": 10.00 \"currency\": \"EUR\", \"direction\": \"IN\", \"description\": \"x\"}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bodiesBehindMalformedAccountId")
    void checksMalformedAccountIdBeforeBodyWithAccountMissing(String label, String json) {
        // §3 rule 1: a malformed path ID wins over an invalid or malformed body.
        expectProblem(api.postTransactionRaw("not-a-uuid", json), 400, "ACCOUNT_MISSING");
    }

    @Test
    void validatesBodyBeforeAccountExistence() {
        String unknownId = UUID.randomUUID().toString();
        JsonNode problem = expectProblem(
                api.postTransaction(unknownId, "-5.00", "EUR", "IN", "Unknown and negative"), 400, "INVALID_AMOUNT");
        assertErrors(problem, "amount", "INVALID_AMOUNT");
        events.assertNoEventMentions(unknownId);
    }

    @Test
    void checksAccountExistenceBeforeCurrencyHeld() {
        String unknownId = UUID.randomUUID().toString();
        expectProblem(api.postTransaction(unknownId, "10.00", "SEK", "OUT", "Unknown account"), 404, "ACCOUNT_MISSING");
        events.assertNoEventMentions(unknownId);
    }

    @Test
    void checksCurrencyHeldBeforeInsufficientFunds() {
        String accountId = api.createAccount("EUR");
        api.transact(accountId, "10.00", "EUR", "IN", "Opening");
        int before = events.awaitEvents(accountId, 4).size();

        expectProblem(api.postTransaction(accountId, "1000000.00", "USD", "OUT", "Not held and too much"),
                422, "INVALID_CURRENCY");

        assertNothingChanged(accountId, before, 1, "EUR", "10.00");
    }

    // ================================================================ POST events (§5)

    @Test
    void publishesTransactionCreatedThenBalanceUpdatedPerTransaction() {
        String accountId = api.createAccount("EUR", "SEK");

        int beforeIn = events.awaitEvents(accountId, 3).size();
        JsonNode in = api.transact(accountId, "100.00", "EUR", "IN", "Salary");
        assertTransactionEvents(accountId, beforeIn, in);

        int beforeOut = beforeIn + 2;
        JsonNode out = api.transact(accountId, "30.50", "EUR", "OUT", "Groceries");
        assertTransactionEvents(accountId, beforeOut, out);
    }

    // ================================================================ GET (§2, §3)

    @Test
    void listsNoTransactionsForNewAccountAsEmptyArray() {
        String accountId = api.createAccount("EUR");
        EntityExchangeResult<String> result = api.getTransactionsRaw(accountId);
        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(200);
        assertJsonContentType(result);
        JsonNode body = JSON.readTree(result.getResponseBody());
        assertThat(body.isArray()).as("body %s", body).isTrue();
        assertThat(body.size()).isZero();
    }

    @Test
    void listsTransactionsInPostOrderWithRunningBalancePerCurrency() {
        String accountId = api.createAccount("EUR", "USD");
        List<JsonNode> posted = new ArrayList<>();
        posted.add(api.transact(accountId, "100.00", "EUR", "IN", "One"));
        posted.add(api.transact(accountId, "50.00", "USD", "IN", "Two"));
        posted.add(api.transact(accountId, "20.25", "EUR", "OUT", "Three"));
        posted.add(api.transact(accountId, "0.01", "USD", "OUT", "Four"));
        posted.add(api.transact(accountId, "0.50", "EUR", "IN", "Five"));

        EntityExchangeResult<String> result = api.getTransactionsRaw(accountId);
        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(200);
        assertJsonContentType(result);
        List<JsonNode> list = api.listTransactions(accountId);

        assertThat(list).hasSize(5);
        assertTransaction(list.get(0), accountId, "100.00", "EUR", "IN", "One", "100.00");
        assertTransaction(list.get(1), accountId, "50.00", "USD", "IN", "Two", "50.00");
        assertTransaction(list.get(2), accountId, "20.25", "EUR", "OUT", "Three", "79.75");
        assertTransaction(list.get(3), accountId, "0.01", "USD", "OUT", "Four", "49.99");
        assertTransaction(list.get(4), accountId, "0.50", "EUR", "IN", "Five", "80.25");
        for (int i = 0; i < posted.size(); i++) {
            assertThat(list.get(i).get("transactionId").asString())
                    .as("list[%d] is the %d-th posted transaction", i, i)
                    .isEqualTo(posted.get(i).get("transactionId").asString());
            assertThat(list.get(i)).as("list[%d] equals its POST response", i).isEqualTo(posted.get(i));
        }

        Map<String, BigDecimal> balances = balancesOf(api.getAccount(accountId));
        assertThat(balances.get("EUR")).isEqualByComparingTo(new BigDecimal("80.25"));
        assertThat(balances.get("USD")).isEqualByComparingTo(new BigDecimal("49.99"));
    }

    @Test
    void listsOnlyTheRequestedAccountsTransactions() {
        String mine = api.createAccount("EUR");
        String other = api.createAccount("EUR");
        JsonNode m1 = api.transact(mine, "10.00", "EUR", "IN", "Mine 1");
        JsonNode o1 = api.transact(other, "99.00", "EUR", "IN", "Other 1");
        JsonNode m2 = api.transact(mine, "3.00", "EUR", "OUT", "Mine 2");
        api.transact(other, "9.00", "EUR", "OUT", "Other 2");

        List<JsonNode> list = api.listTransactions(mine);
        assertThat(list).extracting(t -> t.get("transactionId").asString())
                .containsExactly(m1.get("transactionId").asString(), m2.get("transactionId").asString());
        assertThat(list).extracting(t -> t.get("accountId").asString()).containsOnly(mine);

        List<JsonNode> otherList = api.listTransactions(other);
        assertThat(otherList).hasSize(2);
        assertThat(otherList.get(0).get("transactionId").asString()).isEqualTo(o1.get("transactionId").asString());
        assertThat(otherList).extracting(t -> t.get("accountId").asString()).containsOnly(other);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "1-2-3-4-5", "123e4567e89b12d3a456426614174000"})
    void rejectsMalformedAccountIdOnListWith400InvalidAccount(String malformedId) {
        expectProblem(api.getTransactionsRaw(malformedId), 400, "INVALID_ACCOUNT");
    }

    @Test
    void rejectsUnknownAccountIdOnListWith404InvalidAccount() {
        expectProblem(api.getTransactionsRaw(UUID.randomUUID().toString()), 404, "INVALID_ACCOUNT");
    }

    @Test
    void listingTransactionsPublishesNoEvent() {
        String accountId = api.createAccount("EUR");
        api.transact(accountId, "1.00", "EUR", "IN", "Opening");
        List<Event> before = events.awaitEvents(accountId, 4);
        api.listTransactions(accountId);
        events.fence();
        assertThat(events.events(accountId)).as("no event after listing").isEqualTo(before);
    }

    // ================================================================ helpers

    /** Builds a transaction body from raw JSON tokens; {@link #ABSENT} leaves the field out. */
    private static String body(String amount, String currency, String direction, String description) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("amount", amount);
        fields.put("currency", currency);
        fields.put("direction", direction);
        fields.put("description", description);
        List<String> parts = new ArrayList<>();
        fields.forEach((name, value) -> {
            if (value != null) {
                parts.add("\"" + name + "\": " + value);
            }
        });
        return "{" + String.join(", ", parts) + "}";
    }

    private static void assertTransaction(JsonNode tx, String accountId, String amount, String currency,
                                          String direction, String description, String balanceAfter) {
        assertThat(fieldNames(tx)).as("Transaction fields of %s", tx)
                .containsExactlyInAnyOrderElementsOf(TRANSACTION_FIELDS);
        assertThat(tx.get("accountId").asString()).isEqualTo(accountId);
        String transactionId = tx.get("transactionId").asString();
        assertThat(UUID.fromString(transactionId).toString()).isEqualTo(transactionId);
        assertMoney(tx.get("amount"), amount, "amount");
        assertThat(tx.get("currency").asString()).isEqualTo(currency);
        assertThat(tx.get("direction").asString()).isEqualTo(direction);
        assertThat(tx.get("description").asString()).isEqualTo(description);
        assertMoney(tx.get("balanceAfter"), balanceAfter, "balanceAfter");
    }

    private static void assertMoney(JsonNode node, String expected, String name) {
        assertThat(node).as(name).isNotNull();
        assertThat(node.isNumber()).as("%s is a JSON number: %s", name, node).isTrue();
        assertThat(node.decimalValue()).as(name).isEqualByComparingTo(new BigDecimal(expected));
        assertThat(node.decimalValue().scale()).as("%s scale", name).isEqualTo(2);
    }

    private static Map<String, BigDecimal> balancesOf(JsonNode account) {
        Map<String, BigDecimal> balances = new LinkedHashMap<>();
        for (JsonNode balance : account.get("balances")) {
            balances.put(balance.get("currency").asString(), balance.get("availableAmount").decimalValue());
        }
        return balances;
    }

    /** A rejected request published no event, added no transaction and left the balance unchanged. */
    private void assertNothingChanged(String accountId, int eventsBefore, int transactionsBefore,
                                      String currency, String balance) {
        events.fence();
        assertThat(events.events(accountId)).as("no new events").hasSize(eventsBefore);
        assertThat(api.balance(accountId, currency)).isEqualByComparingTo(new BigDecimal(balance));
        assertThat(api.listTransactions(accountId)).as("no transaction added").hasSize(transactionsBefore);
    }

    /**
     * Waits for the 2 events of {@code tx}, which follow the account's first {@code before} events, asserts
     * them against §5 and returns them.
     */
    private List<Event> assertTransactionEvents(String accountId, int before, JsonNode tx) {
        List<Event> published = events.awaitEvents(accountId, before + 2);
        assertThat(published).as("events of %s", accountId).hasSize(before + 2);
        List<Event> added = published.subList(before, before + 2);
        assertThat(routingKeys(added)).containsExactly("transaction.created", "balance.updated");

        List<JsonNode> datas = new ArrayList<>();
        for (Event event : added) {
            assertEnvelope(event, accountId);
            datas.add(event.data());
        }
        assertThat(added.get(0).eventId()).isNotEqualTo(added.get(1).eventId());

        JsonNode created = datas.get(0);
        assertThat(fieldNames(created)).as("§5 data fields, in order").containsExactly(
                "transactionId", "accountId", "amount", "currency", "direction", "description", "balanceAfter");
        assertThat(created.get("transactionId").asString()).isEqualTo(tx.get("transactionId").asString());
        assertThat(created.get("accountId").asString()).isEqualTo(accountId);
        assertMoney(created.get("amount"), tx.get("amount").decimalValue().toPlainString(), "data.amount");
        assertThat(created.get("currency").asString()).isEqualTo(tx.get("currency").asString());
        assertThat(created.get("direction").asString()).isEqualTo(tx.get("direction").asString());
        assertThat(created.get("description").asString()).isEqualTo(tx.get("description").asString());
        assertMoney(created.get("balanceAfter"), tx.get("balanceAfter").decimalValue().toPlainString(),
                "data.balanceAfter");

        JsonNode updated = datas.get(1);
        assertThat(fieldNames(updated)).as("§5 data fields, in order").containsExactly(
                "accountId", "currency", "availableAmount", "transactionId");
        assertThat(updated.get("accountId").asString()).isEqualTo(accountId);
        assertThat(updated.get("currency").asString()).isEqualTo(tx.get("currency").asString());
        assertMoney(updated.get("availableAmount"), tx.get("balanceAfter").decimalValue().toPlainString(),
                "data.availableAmount");
        assertThat(updated.get("transactionId").asString()).isEqualTo(tx.get("transactionId").asString());
        return added;
    }
}
