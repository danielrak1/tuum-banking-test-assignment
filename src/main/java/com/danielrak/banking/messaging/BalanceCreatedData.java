package com.danielrak.banking.messaging;

import com.danielrak.banking.domain.Currency;
import java.math.BigDecimal;
import java.util.UUID;

public record BalanceCreatedData(UUID accountId, Currency currency, BigDecimal availableAmount) {
}
