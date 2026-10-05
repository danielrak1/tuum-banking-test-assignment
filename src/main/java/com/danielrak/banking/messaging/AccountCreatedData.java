package com.danielrak.banking.messaging;

import java.util.UUID;

public record AccountCreatedData(UUID accountId, String customerId, String country) {
}
