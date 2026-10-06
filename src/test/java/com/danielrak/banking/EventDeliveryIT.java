package com.danielrak.banking;

import static com.danielrak.banking.BankingApi.fieldNames;
import static com.danielrak.banking.BankingEvents.assertEnvelope;
import static com.danielrak.banking.BankingEvents.distinctByEventId;
import static com.danielrak.banking.BankingEvents.routingKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.danielrak.banking.BankingEvents.Event;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;

/**
 * Success criterion 4 (intent.md), design.md §6: events are never lost. While the broker is paused, a
 * committed transaction's outbox rows stay pending; once it is back, its events arrive on
 * {@code banking.events.all} and the rows are gone. Delivery is at-least-once (§5), so a message published
 * during the outage may arrive twice: this test dedupes on {@code eventId}.
 */
@IntegrationTest
class EventDeliveryIT {

    /** How long the publisher may take to recover after the broker comes back (reconnect, confirm timeouts). */
    private static final Duration RECOVERY = Duration.ofSeconds(30);

    @Autowired
    RestTestClient client;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    RabbitMQContainer rabbitContainer;

    BankingApi api;
    BankingEvents events;

    @BeforeEach
    void setUp() {
        api = new BankingApi(client);
        events = new BankingEvents(rabbitTemplate, api, jdbc);
    }

    @Test
    void publishesTransactionCommittedWhileBrokerPausedOnceItIsBackThenKeepsPublishing() {
        String accountId = api.createAccount("EUR");
        List<Event> opening = events.awaitEvents(accountId, 2);
        assertThat(routingKeys(opening)).containsExactly("account.created", "balance.created");
        int before = opening.size();

        JsonNode tx;
        pauseBroker();
        try {
            // §6: the API keeps accepting writes while the broker is down; the events wait in the outbox.
            tx = api.transact(accountId, "25.00", "EUR", "IN", "During outage");
            assertTransaction(tx, accountId, "25.00", "IN", "During outage", "25.00");

            // Rows are deleted only once the broker confirms (§4), so "still 2" means "not published".
            // The queue itself can't be read here: a basic.get on the paused broker blocks until it is back.
            assertThat(events.pendingOutboxRows(accountId)).as("outbox rows pending while paused").isEqualTo(2);
            await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(events.pendingOutboxRows(accountId)).as("rows stay pending").isEqualTo(2));
        } finally {
            unpauseBroker();
        }

        await().atMost(RECOVERY).untilAsserted(() -> {
            List<Event> distinct = distinctByEventId(events.events(accountId));
            assertThat(distinct).as("distinct events of %s", accountId).hasSize(before + 2);
            assertThat(events.pendingOutboxRows(accountId)).as("outbox empty after recovery").isZero();
        });
        List<Event> recovered = distinctByEventId(events.events(accountId)).subList(before, before + 2);
        assertTransactionEvents(recovered, accountId, tx);

        // Publishing has recovered: a fresh transaction's events arrive within the normal timeout.
        JsonNode fresh = api.transact(accountId, "5.00", "EUR", "OUT", "After outage");
        assertTransaction(fresh, accountId, "5.00", "OUT", "After outage", "20.00");
        String freshId = fresh.get("transactionId").asString();
        List<Event> freshEvents = await().atMost(BankingEvents.TIMEOUT).until(
                () -> distinctByEventId(events.events(accountId)).stream()
                        .filter(e -> freshId.equals(e.data().path("transactionId").asString()))
                        .toList(),
                found -> found.size() >= 2);
        assertTransactionEvents(freshEvents, accountId, fresh);
        await().atMost(BankingEvents.TIMEOUT).untilAsserted(() ->
                assertThat(events.pendingOutboxRows(accountId)).as("outbox empty").isZero());
    }

    // ---------------------------------------------------------------- helpers

    private void pauseBroker() {
        DockerClientFactory.instance().client().pauseContainerCmd(rabbitContainer.getContainerId()).exec();
    }

    private void unpauseBroker() {
        DockerClientFactory.instance().client().unpauseContainerCmd(rabbitContainer.getContainerId()).exec();
    }

    /** The §2 Transaction body of a 201 on the EUR balance. */
    private static void assertTransaction(JsonNode tx, String accountId, String amount, String direction,
                                          String description, String balanceAfter) {
        assertThat(fieldNames(tx)).as("Transaction fields of %s", tx).containsExactlyInAnyOrder(
                "accountId", "transactionId", "amount", "currency", "direction", "description", "balanceAfter");
        assertThat(tx.get("accountId").asString()).isEqualTo(accountId);
        String transactionId = tx.get("transactionId").asString();
        assertThat(UUID.fromString(transactionId).toString()).isEqualTo(transactionId);
        assertMoney(tx.get("amount"), amount, "amount");
        assertThat(tx.get("currency").asString()).isEqualTo("EUR");
        assertThat(tx.get("direction").asString()).isEqualTo(direction);
        assertThat(tx.get("description").asString()).isEqualTo(description);
        assertMoney(tx.get("balanceAfter"), balanceAfter, "balanceAfter");
    }

    /** Exactly {@code transaction.created} then {@code balance.updated} for {@code tx}, per §5. */
    private static void assertTransactionEvents(List<Event> added, String accountId, JsonNode tx) {
        assertThat(routingKeys(added)).containsExactly("transaction.created", "balance.updated");
        added.forEach(e -> assertEnvelope(e, accountId));
        String transactionId = tx.get("transactionId").asString();
        String balanceAfter = tx.get("balanceAfter").decimalValue().toPlainString();

        JsonNode created = added.get(0).data();
        assertThat(fieldNames(created)).as("§5 data fields, in order").containsExactly(
                "transactionId", "accountId", "amount", "currency", "direction", "description", "balanceAfter");
        assertThat(created.get("transactionId").asString()).isEqualTo(transactionId);
        assertThat(created.get("accountId").asString()).isEqualTo(accountId);
        assertMoney(created.get("amount"), tx.get("amount").decimalValue().toPlainString(), "data.amount");
        assertThat(created.get("currency").asString()).isEqualTo(tx.get("currency").asString());
        assertThat(created.get("direction").asString()).isEqualTo(tx.get("direction").asString());
        assertThat(created.get("description").asString()).isEqualTo(tx.get("description").asString());
        assertMoney(created.get("balanceAfter"), balanceAfter, "data.balanceAfter");

        JsonNode updated = added.get(1).data();
        assertThat(fieldNames(updated)).as("§5 data fields, in order").containsExactly(
                "accountId", "currency", "availableAmount", "transactionId");
        assertThat(updated.get("accountId").asString()).isEqualTo(accountId);
        assertThat(updated.get("currency").asString()).isEqualTo(tx.get("currency").asString());
        assertMoney(updated.get("availableAmount"), balanceAfter, "data.availableAmount");
        assertThat(updated.get("transactionId").asString()).isEqualTo(transactionId);
    }

    private static void assertMoney(JsonNode node, String expected, String name) {
        assertThat(node).as(name).isNotNull();
        assertThat(node.isNumber()).as("%s is a JSON number: %s", name, node).isTrue();
        assertThat(node.decimalValue()).as(name).isEqualByComparingTo(new BigDecimal(expected));
        assertThat(node.decimalValue().scale()).as("%s scale", name).isEqualTo(2);
    }
}
