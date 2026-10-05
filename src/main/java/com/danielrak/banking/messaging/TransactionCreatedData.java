package com.danielrak.banking.messaging;

import com.danielrak.banking.domain.Currency;
import com.danielrak.banking.domain.Direction;
import com.danielrak.banking.domain.Transaction;
import java.math.BigDecimal;
import java.util.UUID;

public record TransactionCreatedData(UUID transactionId, UUID accountId, BigDecimal amount, Currency currency,
        Direction direction, String description, BigDecimal balanceAfter) {

    public static TransactionCreatedData from(Transaction t) {
        return new TransactionCreatedData(t.id(), t.accountId(), t.amount(), t.currency(), t.direction(),
                t.description(), t.balanceAfter());
    }
}
