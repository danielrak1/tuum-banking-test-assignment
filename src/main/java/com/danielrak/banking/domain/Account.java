package com.danielrak.banking.domain;

import java.util.List;
import java.util.UUID;

/** An account with its balances, ordered by currency. */
public record Account(UUID id, String customerId, String country, List<Balance> balances) {
}
