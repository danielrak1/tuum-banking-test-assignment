package com.danielrak.banking;

import static com.danielrak.banking.BankingApi.JSON;
import static com.danielrak.banking.BankingApi.assertJsonContentType;
import static com.danielrak.banking.BankingApi.expectProblem;
import static com.danielrak.banking.BankingEvents.assertEnvelope;
import static com.danielrak.banking.BankingEvents.routingKeys;
import static org.assertj.core.api.Assertions.assertThat;

import com.danielrak.banking.BankingEvents.Event;
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
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

/** Black-box contract tests for the design.md §3 "Protocol errors (not in PDF)" table. */
@IntegrationTest
class ProtocolErrorsIT {

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

    @Test
    void rejectsUnknownRouteWith404NotFound() {
        String accountId = api.createAccount("EUR");
        EntityExchangeResult<String> result = client.get().uri("/accounts/{id}/foo", accountId)
                .exchange()
                .returnResult(String.class);
        expectProblem(result, 404, "NOT_FOUND");
    }

    @Test
    void rejectsUnsupportedMethodWith405MethodNotAllowed() {
        String accountId = api.createAccount("EUR");
        EntityExchangeResult<String> result = client.delete().uri("/accounts/{id}", accountId)
                .exchange()
                .returnResult(String.class);
        expectProblem(result, 405, "METHOD_NOT_ALLOWED");

        // The account is untouched and no event was written for the rejected request.
        EntityExchangeResult<String> get = client.get().uri("/accounts/{id}", accountId)
                .exchange()
                .returnResult(String.class);
        assertThat(get.getStatus().value()).isEqualTo(200);
        events.fence();
        assertThat(routingKeys(events.events(accountId)))
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
        events.assertNoEventMentions(customerId);
    }

    @Test
    void rejectsUnacceptableAcceptHeaderWith406BadRequest() {
        String accountId = api.createAccount("EUR");
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

    @ParameterizedTest(name = "Accept: {0}")
    @ValueSource(strings = {"application/xml", "application/problem+json"})
    void rejectsUnacceptableAcceptOnPostTransactionWith406AndPostsNothing(String accept) {
        String accountId = accountWith100Eur();
        int before = events.awaitEvents(accountId, 4).size();

        EntityExchangeResult<String> result = postTransaction(
                accountId, MediaType.APPLICATION_JSON, MediaType.parseMediaType(accept), OUT_30);

        assertNotAcceptable(result, "POST transaction, Accept: " + accept);
        assertTransactionNotPosted(accountId, before);
    }

    @ParameterizedTest(name = "Content-Type: {0}")
    @ValueSource(strings = {"application/vnd.x+json", "application/problem+json", "text/plain"})
    void rejectsUnsupportedContentTypeOnPostTransactionWith415AndPostsNothing(String contentType) {
        String accountId = accountWith100Eur();
        int before = events.awaitEvents(accountId, 4).size();

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
        events.assertNoEventMentions(customerId);
    }

    @Test
    void rejectsVendorJsonContentTypeOnCreateAccountWith415AndCreatesNothing() {
        String customerId = "C-" + UUID.randomUUID();
        EntityExchangeResult<String> result = postAccount(
                customerId, MediaType.parseMediaType("application/vnd.x+json"), null);
        expectProblem(result, 415, "UNSUPPORTED_MEDIA_TYPE");
        events.assertNoEventMentions(customerId);
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
        int before = events.awaitEvents(accountId, 4).size();

        EntityExchangeResult<String> result = postTransaction(accountId, contentType, accept, OUT_30);

        assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(201);
        assertJsonContentType(result);
        JsonNode tx = JSON.readTree(result.getResponseBody());
        BigDecimal balanceAfter = tx.get("balanceAfter").decimalValue();
        assertThat(balanceAfter).isEqualByComparingTo(new BigDecimal("70.00"));
        assertThat(balanceAfter.scale()).isEqualTo(2);
        assertThat(api.balance(accountId, "EUR")).isEqualByComparingTo(new BigDecimal("70.00"));
        assertThat(api.listTransactions(accountId)).hasSize(2);
        List<Event> published = events.awaitEvents(accountId, before + 2);
        assertThat(published).as("events of %s", accountId).hasSize(before + 2);
        List<Event> added = published.subList(before, before + 2);
        assertThat(routingKeys(added)).containsExactly("transaction.created", "balance.updated");
        added.forEach(e -> assertEnvelope(e, accountId));
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
        List<Event> published = events.awaitEvents(accountId, 2);
        assertThat(published).as("events of %s", accountId).hasSize(2);
        assertThat(routingKeys(published)).containsExactly("account.created", "balance.created");
        published.forEach(e -> assertEnvelope(e, accountId));
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

    /** A rejected request left the balance and the transaction list unchanged, and published nothing. */
    private void assertTransactionNotPosted(String accountId, int eventsBefore) {
        events.fence();
        assertThat(events.events(accountId)).as("no new events").hasSize(eventsBefore);
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

    // ---------------------------------------------------------------- helpers

}
