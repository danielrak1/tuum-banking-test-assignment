package com.danielrak.banking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
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
