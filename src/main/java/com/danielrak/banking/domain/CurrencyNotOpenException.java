package com.danielrak.banking.domain;

import java.util.UUID;

/**
 * The currency is supported, but the account has no balance in it. The API maps it to 422
 * {@code INVALID_CURRENCY}. Unchecked, so the {@code @Transactional} service rolls back.
 */
public class CurrencyNotOpenException extends RuntimeException {

    public CurrencyNotOpenException(UUID accountId, Currency currency) {
        super("Account " + accountId + " has no " + currency + " balance");
    }
}
