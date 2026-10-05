package com.danielrak.banking.api;

import com.danielrak.banking.domain.Account;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record AccountResponse(UUID accountId, String customerId, String country, List<BalanceResponse> balances) {

    public record BalanceResponse(String currency, BigDecimal availableAmount) {
    }

    static AccountResponse from(Account account) {
        return new AccountResponse(account.id(), account.customerId(), account.country(),
                account.balances().stream()
                        .map(b -> new BalanceResponse(b.currency().name(), b.availableAmount()))
                        .toList());
    }
}
