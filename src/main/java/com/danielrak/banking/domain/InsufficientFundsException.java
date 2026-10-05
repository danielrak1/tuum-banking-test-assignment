package com.danielrak.banking.domain;

import java.util.UUID;

/**
 * An OUT larger than the available balance (ADR-0002). The API maps it to 422
 * {@code INSUFFICIENT_FUNDS}. Unchecked, so the {@code @Transactional} service rolls back.
 */
public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(UUID accountId, Currency currency) {
        super("Insufficient " + currency + " funds on account " + accountId);
    }
}
