package com.danielrak.banking.messaging;

import com.danielrak.banking.domain.Currency;
import java.math.BigDecimal;
import java.util.UUID;

/** A balance's state after a transaction, with the {@code transactionId} that changed it (ADR-0004). */
public record BalanceUpdatedData(UUID accountId, Currency currency, BigDecimal availableAmount, UUID transactionId) {
}
