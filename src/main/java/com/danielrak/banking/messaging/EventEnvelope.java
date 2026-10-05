package com.danielrak.banking.messaging;

import java.time.Instant;
import java.util.UUID;

/** The envelope every message carries (design.md §5). {@code data} is the record's full state after the change. */
public record EventEnvelope(UUID eventId, String eventType, Instant occurredAt, UUID accountId, Object data) {
}
