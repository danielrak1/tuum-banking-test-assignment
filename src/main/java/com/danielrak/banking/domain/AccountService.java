package com.danielrak.banking.domain;

import com.danielrak.banking.messaging.AccountCreatedData;
import com.danielrak.banking.messaging.BalanceCreatedData;
import com.danielrak.banking.messaging.EventTypes;
import com.danielrak.banking.messaging.OutboxWriter;
import com.danielrak.banking.persistence.AccountMapper;
import com.danielrak.banking.persistence.AccountRow;
import com.danielrak.banking.persistence.BalanceMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountMapper accountMapper;
    private final BalanceMapper balanceMapper;
    private final OutboxWriter outboxWriter;

    public AccountService(AccountMapper accountMapper, BalanceMapper balanceMapper, OutboxWriter outboxWriter) {
        this.accountMapper = accountMapper;
        this.balanceMapper = balanceMapper;
        this.outboxWriter = outboxWriter;
    }

    /**
     * Opens an account with a zero balance in each currency, and writes one event per inserted row:
     * {@code account.created}, then {@code balance.created} per currency (ADR-0004).
     */
    @Transactional
    public Account create(String customerId, String country, List<Currency> currencies) {
        UUID id = UUID.randomUUID();
        accountMapper.insert(id, customerId, country);
        currencies.forEach(currency -> balanceMapper.insert(id, currency));
        // Read back, so events and the response carry the stored state (amount defaults to 0 in the DB).
        List<Balance> balances = balanceMapper.findByAccountId(id);

        outboxWriter.write(EventTypes.ACCOUNT_CREATED, id, new AccountCreatedData(id, customerId, country));
        balances.forEach(balance -> outboxWriter.write(EventTypes.BALANCE_CREATED, id,
                new BalanceCreatedData(id, balance.currency(), balance.availableAmount())));

        return new Account(id, customerId, country, balances);
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        AccountRow row = accountMapper.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
        return new Account(row.id(), row.customerId(), row.country(), balanceMapper.findByAccountId(id));
    }
}
