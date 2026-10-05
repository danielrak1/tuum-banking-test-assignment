package com.danielrak.banking.domain;

/** IN adds to a balance, OUT subtracts from it. Matches the CHECK on {@code account_transaction.direction}. */
public enum Direction {
    IN, OUT
}
