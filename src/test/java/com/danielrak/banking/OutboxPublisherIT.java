package com.danielrak.banking;

import static com.danielrak.banking.BankingEvents.distinctByEventId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.danielrak.banking.BankingEvents.Event;
import com.danielrak.banking.messaging.OutboxPublisher;
import com.github.dockerjava.api.DockerClient;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;

/**
 * White-box tests of the ADR-0003 publisher:
 * <ul>
 *   <li>the advisory lock: while another instance holds it, this one publishes nothing and the rows stay
 *       pending; once it is released, they are published in order and deleted;</li>
 *   <li>the failure path: a broker paused for longer than {@code banking.outbox.confirm-timeout} (2 s)
 *       makes a batch time out, the rows stay pending and are retried, and arrive (possibly twice) once
 *       the broker is back.</li>
 * </ul>
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class OutboxPublisherIT {

    /** Longer than the 2 s confirm timeout, so at least one batch times out while the broker is paused. */
    private static final Duration PAUSE = Duration.ofSeconds(3);

    @Autowired
    RestTestClient client;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    RabbitMQContainer rabbitContainer;

    BankingApi api;
    BankingEvents events;

    @BeforeEach
    void setUp() {
        api = new BankingApi(client);
        events = new BankingEvents(rabbit, api, jdbc);
    }

    @Test
    void publishesNothingWhileAnotherInstanceHoldsTheLockThenDrainsInOrder() throws SQLException {
        String accountId;
        try (Connection otherInstance = dataSource.getConnection()) {
            advisoryLock(otherInstance, "pg_advisory_lock");
            try {
                accountId = api.createAccount("EUR");
                api.transact(accountId, "10.00", "EUR", "IN", "Held back");

                await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                    assertThat(events.pendingOutboxRows(accountId)).as("rows stay pending").isEqualTo(4);
                    assertThat(events.events(accountId)).as("nothing published").isEmpty();
                });
            } finally {
                // A pooled connection outlives close(), and so would a session lock: release it explicitly.
                advisoryLock(otherInstance, "pg_advisory_unlock");
            }
        }

        List<Event> published = events.awaitEvents(accountId, 4);
        await().atMost(BankingEvents.TIMEOUT).untilAsserted(() ->
                assertThat(events.pendingOutboxRows(accountId)).as("published rows are deleted").isZero());

        assertThat(published).extracting(Event::routingKey)
                .containsExactly("account.created", "balance.created", "transaction.created", "balance.updated");
        for (Event event : published) {
            assertThat(event.exchange()).isEqualTo(BankingEvents.EXCHANGE);
            assertThat(event.contentType()).isEqualTo("application/json");
            assertThat(event.deliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(event.messageId()).isEqualTo(event.eventId());
            assertThat(event.eventType()).isEqualTo(event.routingKey());
        }
    }

    @Test
    void keepsRowsPendingThroughAConfirmTimeoutAndPublishesThemOnceTheBrokerIsBack(CapturedOutput output) {
        String accountId = api.createAccount("EUR");
        int before = events.awaitEvents(accountId, 2).size();

        JsonNode tx;
        DockerClient docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(rabbitContainer.getContainerId()).exec();
        try {
            tx = api.transact(accountId, "7.00", "EUR", "IN", "Unconfirmed");
            // Rows are deleted only on a confirm, so "still 2" across the timeout means "not published".
            // The queue can't be read here: basic.get blocks on a paused broker.
            await().during(PAUSE).atMost(PAUSE.plusSeconds(2)).untilAsserted(() ->
                    assertThat(events.pendingOutboxRows(accountId)).as("rows stay pending").isEqualTo(2));
        } finally {
            docker.unpauseContainerCmd(rabbitContainer.getContainerId()).exec();
        }

        // At-least-once: a message sent before its confirm timed out may arrive again after the retry.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(distinctByEventId(events.events(accountId))).hasSize(before + 2);
            assertThat(events.pendingOutboxRows(accountId)).as("published rows are deleted").isZero();
        });
        List<Event> published = distinctByEventId(events.events(accountId)).subList(before, before + 2);
        assertThat(published).extracting(Event::routingKey).containsExactly("transaction.created", "balance.updated");
        assertThat(published).extracting(e -> e.data().path("transactionId").asString())
                .containsOnly(tx.get("transactionId").asString());

        assertThat(output.getOut()).contains("Outbox publishing failed, rows stay pending and are retried");
        await().atMost(BankingEvents.TIMEOUT).until(() -> output.getOut().contains("Outbox publishing recovered"));
    }

    private static void advisoryLock(Connection connection, String function) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + function + "(?)")) {
            statement.setLong(1, OutboxPublisher.LOCK_KEY);
            statement.execute();
        }
    }
}
