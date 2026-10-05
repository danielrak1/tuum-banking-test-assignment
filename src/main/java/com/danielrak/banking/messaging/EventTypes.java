package com.danielrak.banking.messaging;

/** Event types, which double as routing keys (design.md §5). */
public final class EventTypes {

    public static final String ACCOUNT_CREATED = "account.created";
    public static final String BALANCE_CREATED = "balance.created";

    private EventTypes() {
    }
}
