package com.danielrak.banking.persistence;

import java.util.UUID;

/** A pending {@code outbox_event} row; {@code payload} is the envelope JSON exactly as written. */
public record OutboxRow(long id, UUID eventId, String routingKey, String payload) {
}
