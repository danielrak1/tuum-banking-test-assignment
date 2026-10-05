package com.danielrak.banking.api;

import com.danielrak.banking.domain.Transaction;
import java.math.BigDecimal;
import java.util.UUID;

public record TransactionResponse(UUID accountId, UUID transactionId, BigDecimal amount, String currency,
        String direction, String description, BigDecimal balanceAfter) {

    static TransactionResponse from(Transaction t) {
        return new TransactionResponse(t.accountId(), t.id(), t.amount(), t.currency().name(), t.direction().name(),
                t.description(), t.balanceAfter());
    }
}
