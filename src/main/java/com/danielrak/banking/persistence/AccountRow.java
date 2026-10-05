package com.danielrak.banking.persistence;

import java.util.UUID;

/** A row of {@code account}, without its balances. */
public record AccountRow(UUID id, String customerId, String country) {
}
