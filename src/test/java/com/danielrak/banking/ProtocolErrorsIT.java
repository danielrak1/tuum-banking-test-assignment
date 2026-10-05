package com.danielrak.banking;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Black-box contract tests for the design.md §3 "Protocol errors (not in PDF)" table. */
@IntegrationTest
class ProtocolErrorsIT {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    @Autowired
    RestTestClient client;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void rejectsUnknownRouteWith404NotFound() {
        String accountId = createAccount();
        EntityExchangeResult<String> result = client.get().uri("/accounts/{id}/foo", accountId)
                .exchange()
                .returnResult(String.class);
        expectProblem(result, 404, "NOT_FOUND");
    }

    @Test
    void rejectsUnsupportedMethodWith405MethodNotAllowed() {
        String accountId = createAccount();
        EntityExchangeResult<String> result = client.delete().uri("/accounts/{id}", accountId)
                .exchange()
                .returnResult(String.class);
        expectProblem(result, 405, "METHOD_NOT_ALLOWED");

        // The account is untouched and no event was written for the rejected request.
        EntityExchangeResult<String> get = client.get().uri("/accounts/{id}", accountId)
                .exchange()
                .returnResult(String.class);
        assertThat(get.getStatus().value()).isEqualTo(200);
        assertThat(jdbc.queryForList(
                "SELECT routing_key FROM outbox_event WHERE payload->>'accountId' = ? ORDER BY id",
                String.class, accountId))
                .containsExactly("account.created", "balance.created");
    }

    @Test
    void rejectsTextPlainContentTypeWith415UnsupportedMediaType() {
        String customerId = "C-" + UUID.randomUUID();
        EntityExchangeResult<String> result = client.post().uri("/accounts")
                .contentType(MediaType.TEXT_PLAIN)
                .body("""
                      {"customerId": "%s", "country": "EE", "currencies": ["EUR"]}
                      """.formatted(customerId))
                .exchange()
                .returnResult(String.class);
        expectProblem(result, 415, "UNSUPPORTED_MEDIA_TYPE");
        assertNoOutboxRowMentions(customerId);
    }

    @Test
    void rejectsUnacceptableAcceptHeaderWith406BadRequest() {
        String accountId = createAccount();
        EntityExchangeResult<String> result = client.get().uri("/accounts/{id}", accountId)
                .accept(MediaType.APPLICATION_XML)
                .exchange()
                .returnResult(String.class);
        expectProblem(result, 406, "BAD_REQUEST");
    }

    // ---------------------------------------------------------------- content negotiation never posts (§3)

    private static final String OUT_30 = """
            {"amount": 30.00, "currency": "EUR", "direction": "OUT", "description": "Negotiated"}
            """;

    BankingApi api;

    @BeforeEach
    void setUp() {
        api = new BankingApi(client, jdbc);
    }

    @ParameterizedTest(name = "Accept: {0}")
    @ValueSource(strings = {"application/xml", "application/problem+json"})
    void rejectsUnacceptableAcceptOnPostTransactionWith406AndPostsNothing(String accept) {
        String accountId = accountWith100Eur();
        long before = api.maxOutboxId(accountId);

        EntityExchangeResult<String> result = postTransaction(
                accountId, MediaType.APPLICATION_JSON, MediaType.parseMediaType(accept), OUT_30);

        assertNotAcceptable(result, "POST transaction, Accept: " + accept);
        assertTransactionNotPosted(accountId, before);
    }

    @ParameterizedTest(name = "Content-Type: {0}")
    @ValueSource(strings = {"application/vnd.x+json", "application/problem+json", "text/plain"})
    void rejectsUnsupportedContentTypeOnPostTransactionWith415AndPostsNothing(String contentType) {
        String accountId = accountWith100Eur();
        long before = api.maxOutboxId(accountId);

        EntityExchangeResult<String> result = postTransaction(
                accountId, MediaType.parseMediaType(contentType), null, OUT_30);

        expectProblem(result, 415, "UNSUPPORTED_MEDIA_TYPE");
        assertTransactionNotPosted(accountId, before);
    }

    @Test
    void rejectsXmlAcceptOnCreateAccountWith406AndCreatesNothing() {
        String customerId = "C-" + UUID.randomUUID();
        EntityExchangeResult<String> result = postAccount(
                customerId, MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML);
        assertNotAcceptable(result, "POST /accounts, Accept: application/xml");
        assertNoOutboxRowMentions(customerId);
    }

    @Test
    void rejectsVendorJsonContentTypeOnCreateAccountWith415AndCreatesNothing() {
        String customerId = "C-" + UUID.randomUUID();
        EntityExchangeResult<String> result = postAccount(
                customerId, MediaType.parseMediaType("application/vnd.x+json"), null);
        expectProblem(result, 415, "UNSUPPORTED_MEDIA_TYPE");
        assertNoOutboxRowMentions(customerId);
    }

    static Stream<Arguments> acceptableNegotiation() {
        return Stream.of(
                Arguments.of("Accept: application/json", MediaType.APPLICATION_JSON, MediaType.APPLICATION_JSON),
                Arguments.of("Accept: */*", MediaType.APPLICATION_JSON, MediaType.ALL),
                Arguments.of("Content-Type: application/json;charset=UTF-8",
                        new MediaType("application", "json", StandardCharsets.UTF_8), null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptableNegotiation")
    void postsTransactionWithAcceptableNegotiation(String label, MediaType contentType, MediaType accept) {
        String accountId = accountWith100Eur();
        long before = api.maxOutboxId(accountId);

        EntityExchangeResult<String> result = postTransaction(accountId, contentType, accept, OUT_30);

        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(201);
        assertJsonContentType(result);
        JsonNode tx = JSON.readTree(result.getResponseBody());
        BigDecimal balanceAfter = tx.get("balanceAfter").decimalValue();
        assertThat(balanceAfter).isEqualByComparingTo(new BigDecimal("70.00"));
        assertThat(balanceAfter.scale()).isEqualTo(2);
        assertThat(api.balance(accountId, "EUR")).isEqualByComparingTo(new BigDecimal("70.00"));
        assertThat(api.listTransactions(accountId)).hasSize(2);
        assertThat(api.outboxRowsAfter(accountId, before)).extracting(r -> r.get("routing_key"))
                .containsExactly("transaction.created", "balance.updated");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptableNegotiation")
    void createsAccountWithAcceptableNegotiation(String label, MediaType contentType, MediaType accept) {
        String customerId = "C-" + UUID.randomUUID();
        EntityExchangeResult<String> result = postAccount(customerId, contentType, accept);

        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(201);
        assertJsonContentType(result);
        JsonNode body = JSON.readTree(result.getResponseBody());
        assertThat(body.get("customerId").asString()).isEqualTo(customerId);
        String accountId = body.get("accountId").asString();
        assertThat(jdbc.queryForList(
                "SELECT routing_key FROM outbox_event WHERE payload->>'accountId' = ? ORDER BY id",
                String.class, accountId))
                .containsExactly("account.created", "balance.created");
    }

    /** An EUR account holding 100.00, opened by an IN. */
    private String accountWith100Eur() {
        String accountId = api.createAccount("EUR");
        api.transact(accountId, "100.00", "EUR", "IN", "Opening");
        return accountId;
    }

    private EntityExchangeResult<String> postTransaction(
            String accountId, MediaType contentType, MediaType accept, String json) {
        RestTestClient.RequestBodySpec spec = client.post()
                .uri("/accounts/{id}/transactions", accountId)
                .contentType(contentType);
        if (accept != null) {
            spec = spec.accept(accept);
        }
        return spec.body(json).exchange().returnResult(String.class);
    }

    private EntityExchangeResult<String> postAccount(String customerId, MediaType contentType, MediaType accept) {
        RestTestClient.RequestBodySpec spec = client.post().uri("/accounts").contentType(contentType);
        if (accept != null) {
            spec = spec.accept(accept);
        }
        return spec.body("""
                     {"customerId": "%s", "country": "EE", "currencies": ["EUR"]}
                     """.formatted(customerId))
                .exchange()
                .returnResult(String.class);
    }

    /** A rejected request left the balance, the transaction list and the outbox unchanged. */
    private void assertTransactionNotPosted(String accountId, long outboxBefore) {
        assertThat(api.outboxRowsAfter(accountId, outboxBefore)).as("no new outbox rows").isEmpty();
        assertThat(api.balance(accountId, "EUR")).isEqualByComparingTo(new BigDecimal("100.00"));
        List<JsonNode> list = api.listTransactions(accountId);
        assertThat(list).as("only the opening IN is listed").hasSize(1);
        assertThat(list.get(0).get("direction").asString()).isEqualTo("IN");
    }

    /**
     * 406, and, if a JSON body comes back despite the Accept header, the §3 code BAD_REQUEST and
     * never a Transaction. Prints what was observed, so the report can say.
     */
    private static void assertNotAcceptable(EntityExchangeResult<String> result, String label) {
        MediaType contentType = result.getResponseHeaders().getContentType();
        String body = result.getResponseBody();
        String diagnostics = "%s -> status=%s content-type=%s body=%s".formatted(
                label, result.getStatus(), contentType, body);
        System.out.println("OBSERVED-406 " + diagnostics);
        assertThat(result.getStatus().value()).as(diagnostics).isEqualTo(406);
        boolean jsonBody = body != null && !body.isBlank() && contentType != null
                && (contentType.isCompatibleWith(MediaType.APPLICATION_JSON)
                    || contentType.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        if (jsonBody) {
            JsonNode problem = JSON.readTree(body);
            assertThat(problem.path("code").asString()).as(diagnostics).isEqualTo("BAD_REQUEST");
            assertThat(problem.has("transactionId")).as(diagnostics).isFalse();
        }
    }

    private static void assertJsonContentType(EntityExchangeResult<String> result) {
        MediaType contentType = result.getResponseHeaders().getContentType();
        assertThat(contentType).isNotNull();
        assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_JSON))
                .as("Content-Type %s", contentType).isTrue();
    }

    // ---------------------------------------------------------------- helpers

    private String createAccount() {
        EntityExchangeResult<String> result = client.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                      {"customerId": "C-%s", "country": "EE", "currencies": ["EUR"]}
                      """.formatted(UUID.randomUUID()))
                .exchange()
                .returnResult(String.class);
        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(201);
        return JSON.readTree(result.getResponseBody()).get("accountId").asString();
    }

    private static JsonNode expectProblem(EntityExchangeResult<String> result, int status, String code) {
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

    private void assertNoOutboxRowMentions(String marker) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE payload::text LIKE ?",
                Integer.class, "%" + marker + "%");
        assertThat(count).isZero();
    }
}
