package com.danielrak.banking;

import static com.danielrak.banking.BankingApi.JSON;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

/**
 * Success criterion 3 (intent.md), design.md §6: balances never go negative under concurrent
 * transactions on one balance, the ledger reconciles, and rejected requests emit no events.
 */
@IntegrationTest
class BalanceConcurrencyIT {

    private static final long TIMEOUT_SECONDS = 120;

    @Autowired
    RestTestClient client;

    @Autowired
    JdbcTemplate jdbc;

    BankingApi api;

    @BeforeEach
    void setUp() {
        api = new BankingApi(client, jdbc);
    }

    record Request(String amount, String direction) { }

    record Response(Request request, int status, MediaType contentType, String body) { }

    @Test
    void concurrentOutsSucceedExactlyFloorOfBalanceOverAmountAndNeverGoNegative() throws Exception {
        String accountId = api.createAccount("EUR");
        api.transact(accountId, "100.00", "EUR", "IN", "Opening");
        long outboxAfterOpening = api.maxOutboxId(accountId);

        List<Request> requests = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            requests.add(new Request("7.00", "OUT"));
        }
        List<Response> responses = fireConcurrently(accountId, requests);

        assertOnly201Or422InsufficientFunds(responses);
        List<Response> created = responses.stream().filter(r -> r.status() == 201).toList();
        assertThat(created).as("⌊100/7⌋ OUTs succeed").hasSize(14);
        assertThat(responses.stream().filter(r -> r.status() == 422)).hasSize(36);
        for (Response r : created) {
            assertThat(balanceAfter(JSON.readTree(r.body()))).as("response balanceAfter").isNotNegative();
        }

        BigDecimal stored = api.balance(accountId, "EUR");
        assertThat(stored).isEqualByComparingTo(new BigDecimal("2.00"));

        List<JsonNode> list = api.listTransactions(accountId);
        assertThat(list).hasSize(15);
        for (JsonNode tx : list) {
            assertThat(balanceAfter(tx)).as("listed balanceAfter").isNotNegative();
        }
        assertThat(balanceAfter(list.getLast())).as("last balanceAfter = stored balance")
                .isEqualByComparingTo(stored);
        assertRunningBalance(list);

        assertOutboxMatches(accountId, outboxAfterOpening, created);
    }

    @Test
    void concurrentMixedInsAndOutsReconcileAsConsistentRunningBalance() throws Exception {
        String accountId = api.createAccount("EUR");
        JsonNode opening = api.transact(accountId, "50.00", "EUR", "IN", "Opening");
        long outboxAfterOpening = api.maxOutboxId(accountId);

        List<Request> requests = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            requests.add(i % 3 == 2 ? new Request("5.00", "IN") : new Request("10.00", "OUT"));
        }
        assertThat(requests.stream().filter(r -> r.direction().equals("OUT"))).hasSize(40);
        assertThat(requests.stream().filter(r -> r.direction().equals("IN"))).hasSize(20);

        List<Response> responses = fireConcurrently(accountId, requests);

        assertOnly201Or422InsufficientFunds(responses);
        assertThat(responses.stream().filter(r -> r.request().direction().equals("IN")))
                .as("an IN on a held currency can't fail")
                .allMatch(r -> r.status() == 201);
        List<Response> created = responses.stream().filter(r -> r.status() == 201).toList();
        Map<String, JsonNode> createdById = new HashMap<>();
        for (Response r : created) {
            JsonNode tx = JSON.readTree(r.body());
            assertThat(balanceAfter(tx)).as("response balanceAfter").isNotNegative();
            createdById.put(tx.get("transactionId").asString(), tx);
        }

        BigDecimal stored = api.balance(accountId, "EUR");
        assertThat(stored).isNotNegative();

        List<JsonNode> list = api.listTransactions(accountId);
        assertThat(list).as("opening + every 201").hasSize(1 + created.size());
        assertThat(list.getFirst().get("transactionId").asString())
                .isEqualTo(opening.get("transactionId").asString());
        Set<String> listedIds = new HashSet<>();
        for (JsonNode tx : list.subList(1, list.size())) {
            listedIds.add(tx.get("transactionId").asString());
        }
        assertThat(listedIds).as("listed = the 201 responses").isEqualTo(createdById.keySet());

        for (JsonNode tx : list) {
            assertThat(balanceAfter(tx)).as("listed balanceAfter").isNotNegative();
        }
        for (JsonNode tx : list.subList(1, list.size())) {
            JsonNode response = createdById.get(tx.get("transactionId").asString());
            assertThat(balanceAfter(tx)).as("listed balanceAfter = response balanceAfter")
                    .isEqualByComparingTo(balanceAfter(response));
        }

        // opening + Σ IN − Σ OUT over the listed transactions after the opening = stored balance
        BigDecimal sum = new BigDecimal("50.00");
        for (JsonNode tx : list.subList(1, list.size())) {
            sum = sum.add(signedAmount(tx));
        }
        assertThat(sum).as("ledger sum").isEqualByComparingTo(stored);

        assertThat(balanceAfter(list.getFirst())).isEqualByComparingTo(new BigDecimal("50.00"));
        assertRunningBalance(list);
        assertThat(balanceAfter(list.getLast())).as("last balanceAfter = stored balance")
                .isEqualByComparingTo(stored);

        assertOutboxMatches(accountId, outboxAfterOpening, created);
    }

    // ---------------------------------------------------------------- helpers

    private List<Response> fireConcurrently(String accountId, List<Request> requests) throws Exception {
        int n = requests.size();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Response>> futures = new ArrayList<>();
            for (Request request : requests) {
                Callable<Response> task = () -> {
                    ready.countDown();
                    start.await();
                    EntityExchangeResult<String> result = api.postTransaction(
                            accountId, request.amount(), "EUR", request.direction(), "Concurrent " + request.direction());
                    return new Response(request, result.getStatus().value(),
                            result.getResponseHeaders().getContentType(), result.getResponseBody());
                };
                futures.add(pool.submit(task));
            }
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("all threads ready").isTrue();
            start.countDown();
            List<Response> responses = new ArrayList<>();
            for (Future<Response> future : futures) {
                responses.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertOnly201Or422InsufficientFunds(List<Response> responses) {
        for (Response r : responses) {
            String diagnostics = "%s -> %d %s %s".formatted(r.request(), r.status(), r.contentType(), r.body());
            assertThat(r.status()).as(diagnostics).isIn(201, 422);
            if (r.status() == 422) {
                assertThat(r.contentType()).as(diagnostics).isNotNull();
                assertThat(r.contentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).as(diagnostics).isTrue();
                assertThat(JSON.readTree(r.body()).path("code").asString()).as(diagnostics)
                        .isEqualTo("INSUFFICIENT_FUNDS");
            }
        }
    }

    /** In list order, each balanceAfter is the previous one plus/minus its amount. */
    private static void assertRunningBalance(List<JsonNode> list) {
        BigDecimal previous = BigDecimal.ZERO;
        for (int i = 0; i < list.size(); i++) {
            JsonNode tx = list.get(i);
            BigDecimal expected = previous.add(signedAmount(tx));
            assertThat(balanceAfter(tx)).as("list[%d] %s balanceAfter = previous %s ± amount", i, tx, previous)
                    .isEqualByComparingTo(expected);
            previous = balanceAfter(tx);
        }
    }

    private void assertOutboxMatches(String accountId, long afterId, List<Response> created) {
        List<Map<String, Object>> rows = api.outboxRowsAfter(accountId, afterId);
        assertThat(rows).as("2 outbox rows per 201, none for 422s").hasSize(2 * created.size());
        assertThat(rows.stream().filter(r -> "transaction.created".equals(r.get("routing_key"))))
                .hasSize(created.size());
        assertThat(rows.stream().filter(r -> "balance.updated".equals(r.get("routing_key"))))
                .hasSize(created.size());
    }

    private static BigDecimal balanceAfter(JsonNode tx) {
        return tx.get("balanceAfter").decimalValue();
    }

    private static BigDecimal signedAmount(JsonNode tx) {
        BigDecimal amount = tx.get("amount").decimalValue();
        return switch (tx.get("direction").asString()) {
            case "IN" -> amount;
            case "OUT" -> amount.negate();
            default -> throw new AssertionError("unexpected direction in " + tx);
        };
    }
}
