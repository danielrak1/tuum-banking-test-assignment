package com.danielrak.banking.messaging;

import com.danielrak.banking.persistence.OutboxMapper;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes events as {@code outbox_event} rows in the caller's transaction (ADR-0003). Only the outbox
 * poller publishes them. {@code MANDATORY} makes a call outside a business transaction fail loudly
 * instead of committing an event on its own.
 */
@Component
public class OutboxWriter {

    private final OutboxMapper outboxMapper;

    public OutboxWriter(OutboxMapper outboxMapper) {
        this.outboxMapper = outboxMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(String eventType, UUID accountId, Object data) {
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), eventType, Instant.now(), accountId, data);
        outboxMapper.insert(envelope.eventId(), eventType, EventJson.MAPPER.writeValueAsString(envelope));
    }
}
