package com.danielrak.banking;

import static com.danielrak.banking.BankingApi.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.danielrak.banking.messaging.MessagingConfiguration;
import com.danielrak.banking.messaging.OutboxPublisher;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

/**
 * White-box test of the ADR-0003 advisory lock: while another instance holds the publisher lock, this
 * instance publishes nothing and the rows stay pending; once it is released, they are published in order
 * and deleted.
 */
@IntegrationTest
class OutboxPublisherIT {

    @Autowired
    RestTestClient client;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    RabbitTemplate rabbit;

    BankingApi api;

    /** Messages drained from the demo queue so far, in arrival order. */
    private final List<Message> received = new ArrayList<>();

    @BeforeEach
    void setUp() {
        api = new BankingApi(client, jdbc);
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
                    assertThat(pendingRows(accountId)).as("rows stay pending").isEqualTo(4);
                    assertThat(eventsFor(accountId)).as("nothing published").isEmpty();
                });
            } finally {
                // A pooled connection outlives close(), and so would a session lock: release it explicitly.
                advisoryLock(otherInstance, "pg_advisory_unlock");
            }
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(eventsFor(accountId)).hasSize(4);
            assertThat(pendingRows(accountId)).as("published rows are deleted").isZero();
        });

        List<Message> events = eventsFor(accountId);
        assertThat(events).extracting(m -> m.getMessageProperties().getReceivedRoutingKey())
                .containsExactly("account.created", "balance.created", "transaction.created", "balance.updated");
        for (Message message : events) {
            MessageProperties props = message.getMessageProperties();
            JsonNode envelope = JSON.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            assertThat(props.getReceivedExchange()).isEqualTo(MessagingConfiguration.EXCHANGE);
            assertThat(props.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
            assertThat(props.getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(props.getMessageId()).isEqualTo(envelope.get("eventId").asString());
            assertThat(envelope.get("eventType").asString()).isEqualTo(props.getReceivedRoutingKey());
        }
    }

    private static void advisoryLock(Connection connection, String function) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + function + "(?)")) {
            statement.setLong(1, OutboxPublisher.LOCK_KEY);
            statement.execute();
        }
    }

    private int pendingRows(String accountId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE payload->>'accountId' = ?", Integer.class, accountId);
        return count == null ? 0 : count;
    }

    /** Drains the demo queue, then returns the messages for this account in arrival order. */
    private List<Message> eventsFor(String accountId) {
        Message message;
        while ((message = rabbit.receive(MessagingConfiguration.DEMO_QUEUE)) != null) {
            received.add(message);
        }
        return received.stream()
                .filter(m -> accountId.equals(JSON.readTree(m.getBody()).get("accountId").asString()))
                .toList();
    }
}
