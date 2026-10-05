package com.danielrak.banking.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** A posted transaction; {@code amount} and {@code balanceAfter} have scale 2 (ADR-0001). */
public record Transaction(UUID id, UUID accountId, BigDecimal amount, Currency currency, Direction direction,
        String description, BigDecimal balanceAfter) {
}
