package com.danielrak.banking.domain;

import java.util.UUID;

/** No account has this ID. The API maps it to 404 with the endpoint's not-found code. */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(UUID accountId) {
        super("Account " + accountId + " not found");
    }
}
