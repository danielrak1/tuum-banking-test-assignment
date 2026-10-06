package com.danielrak.banking;

import static com.danielrak.banking.BankingApi.JSON;
import static com.danielrak.banking.BankingApi.fieldNames;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Black-box view of the design.md §5 events, read from the {@code banking.events.all} demo queue.
 *
 * <p>Every test class drains the same queue, so every message goes into one JVM-wide buffer, in arrival
 * order, and a message for another test's account is kept rather than dropped. Tests never consume the
 * queue directly and never purge it.
 *
 * <p>Publishing is asynchronous: wait with {@link #awaitEvents}. To assert that <em>nothing</em> was
 * published, call {@link #fence()} first, never sleep: a write that commits before another starts is
 * published first (§5), so once the fence's events have arrived, any event of an earlier request would
 * have arrived too.
 */
final class BankingEvents {

    static final String EXCHANGE = "banking.events";
    static final String QUEUE = "banking.events.all";
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** Every message drained so far, by any test, in arrival order. Guarded by its own monitor. */
    private static final List<Event> RECEIVED = new ArrayList<>();

    private final RabbitTemplate rabbit;
    private final BankingApi api;
    private final JdbcTemplate jdbc;

    BankingEvents(RabbitTemplate rabbit, BankingApi api, JdbcTemplate jdbc) {
        this.rabbit = rabbit;
        this.api = api;
        this.jdbc = jdbc;
    }

    /** One message as received: its AMQP properties, raw body and parsed §5 envelope. */
    record Event(String exchange, String routingKey, String messageId, String contentType,
                 MessageDeliveryMode deliveryMode, String body, JsonNode envelope) {

        String eventId() {
            return envelope.path("eventId").asString();
        }

        String eventType() {
            return envelope.path("eventType").asString();
        }

        String accountId() {
            return envelope.path("accountId").asString();
        }

        JsonNode data() {
            return envelope.get("data");
        }
    }

    /** The account's events received so far, in arrival order (may include at-least-once duplicates). */
    List<Event> events(String accountId) {
        return received(e -> accountId.equals(e.accountId()));
    }

    /** Waits until the account has at least {@code count} events, then returns all of them so far. */
    List<Event> awaitEvents(String accountId, int count) {
        return await().atMost(TIMEOUT).until(() -> events(accountId), events -> events.size() >= count);
    }

    /** Events whose raw body contains {@code marker}: for rejected requests that have no account ID. */
    /** A rejected request published nothing: after the fence, no event mentions {@code marker}. */
    void assertNoEventMentions(String marker) {
        fence();
        assertThat(eventsMentioning(marker)).as("events mentioning %s", marker).isEmpty();
    }

    List<Event> eventsMentioning(String marker) {
        return received(e -> e.body().contains(marker));
    }

    /**
     * Events matching {@code filter}, from every test: only for "nothing like this was published" checks
     * after {@link #fence()}, never for counts.
     */
    List<Event> eventsMatching(Predicate<Event> filter) {
        return received(filter);
    }

    /**
     * Creates a throwaway account and waits for its events. Afterwards, every event of a request that
     * completed before this call has arrived, so "no event for X" can be asserted without sleeping.
     */
    void fence() {
        String accountId = api.createAccount("EUR");
        awaitEvents(accountId, 2);   // account.created, balance.created
    }

    /** The account's {@code outbox_event} rows not yet published (design.md §4); 0 once all are confirmed. */
    int pendingOutboxRows(String accountId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE payload->>'accountId' = ?", Integer.class, accountId);
        return count == null ? 0 : count;
    }

    /** The events' routing keys, in order. */
    static List<String> routingKeys(List<Event> events) {
        return events.stream().map(Event::routingKey).toList();
    }

    /**
     * Asserts the §5 message properties and envelope of one event of {@code accountId}: exchange
     * {@code banking.events}, routing key = {@code eventType}, {@code application/json}, persistent,
     * {@code message_id} = {@code eventId} (a canonical UUID), {@code occurredAt} an ISO-8601 instant, and
     * the envelope fields in the §5 order. The caller asserts {@code data}.
     */
    static void assertEnvelope(Event event, String accountId) {
        String diagnostics = "%s %s".formatted(event, event.body());
        JsonNode envelope = event.envelope();
        assertThat(fieldNames(envelope)).as("envelope fields, in §5 order: %s", diagnostics)
                .containsExactly("eventId", "eventType", "occurredAt", "accountId", "data");
        assertThat(event.exchange()).as("exchange: %s", diagnostics).isEqualTo(EXCHANGE);
        assertThat(event.routingKey()).as("routing key = eventType: %s", diagnostics).isEqualTo(event.eventType());
        assertThat(event.contentType()).as("content type: %s", diagnostics).isEqualTo("application/json");
        assertThat(event.deliveryMode()).as("delivery mode: %s", diagnostics).isEqualTo(MessageDeliveryMode.PERSISTENT);
        String eventId = event.eventId();
        assertThat(UUID.fromString(eventId).toString()).as("eventId is a canonical UUID: %s", diagnostics)
                .isEqualTo(eventId);
        assertThat(event.messageId()).as("message_id = eventId: %s", diagnostics).isEqualTo(eventId);
        assertThat(Instant.parse(envelope.path("occurredAt").asString())).as("occurredAt: %s", diagnostics)
                .isNotNull();
        assertThat(event.accountId()).as("accountId: %s", diagnostics).isEqualTo(accountId);
        assertThat(event.data()).as("data: %s", diagnostics).isNotNull();
        assertThat(event.data().isObject()).as("data is an object: %s", diagnostics).isTrue();
    }

    /** First occurrence of each {@code eventId}, in order: at-least-once delivery may repeat a message. */
    static List<Event> distinctByEventId(List<Event> events) {
        Map<String, Event> first = new LinkedHashMap<>();
        events.forEach(e -> first.putIfAbsent(e.eventId(), e));
        return new ArrayList<>(first.values());
    }

    private List<Event> received(Predicate<Event> filter) {
        synchronized (RECEIVED) {
            drain();
            return RECEIVED.stream().filter(filter).toList();
        }
    }

    private void drain() {
        Message message;
        while ((message = rabbit.receive(QUEUE)) != null) {
            RECEIVED.add(toEvent(message));
        }
    }

    private static Event toEvent(Message message) {
        MessageProperties props = message.getMessageProperties();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        return new Event(props.getReceivedExchange(), props.getReceivedRoutingKey(), props.getMessageId(),
                props.getContentType(), props.getReceivedDeliveryMode(), body, JSON.readTree(body));
    }
}
