package com.danielrak.banking.domain;

import com.danielrak.banking.messaging.BalanceUpdatedData;
import com.danielrak.banking.messaging.EventTypes;
import com.danielrak.banking.messaging.OutboxWriter;
import com.danielrak.banking.messaging.TransactionCreatedData;
import com.danielrak.banking.persistence.AccountMapper;
import com.danielrak.banking.persistence.BalanceMapper;
import com.danielrak.banking.persistence.TransactionMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionService {

    private final AccountMapper accountMapper;
    private final BalanceMapper balanceMapper;
    private final TransactionMapper transactionMapper;
    private final OutboxWriter outboxWriter;

    public TransactionService(AccountMapper accountMapper, BalanceMapper balanceMapper,
            TransactionMapper transactionMapper, OutboxWriter outboxWriter) {
        this.accountMapper = accountMapper;
        this.balanceMapper = balanceMapper;
        this.transactionMapper = transactionMapper;
        this.outboxWriter = outboxWriter;
    }

    /**
     * Posts a transaction (ADR-0002): account existence, then the currency's balance, then the
     * conditional balance update, then the transaction row and its events, {@code transaction.created}
     * then {@code balance.updated} (ADR-0003, ADR-0004).
     */
    @Transactional
    public Transaction create(UUID accountId, BigDecimal amount, Currency currency, Direction direction,
            String description) {
        requireAccount(accountId);
        if (!balanceMapper.exists(accountId, currency)) {
            throw new CurrencyNotOpenException(accountId, currency);
        }

        // Validation allows at most 2 decimals once trailing zeros are stripped, so this never rounds
        // (10.500 → 10.50, 1E+2 → 100.00); UNNECESSARY asserts that (ADR-0001).
        BigDecimal scaled = amount.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal delta = direction == Direction.IN ? scaled : scaled.negate();
        // The balance row exists (checked above, never deleted), so no row means not enough funds.
        BigDecimal balanceAfter = balanceMapper.applyDelta(accountId, currency, delta)
                .orElseThrow(() -> new InsufficientFundsException(accountId, currency));

        Transaction transaction = new Transaction(UUID.randomUUID(), accountId, scaled, currency, direction,
                description, balanceAfter);
        transactionMapper.insert(transaction);

        outboxWriter.write(EventTypes.TRANSACTION_CREATED, accountId, TransactionCreatedData.from(transaction));
        outboxWriter.write(EventTypes.BALANCE_UPDATED, accountId,
                new BalanceUpdatedData(accountId, currency, balanceAfter, transaction.id()));
        return transaction;
    }

    /** The account's transactions in insert order ({@code seq}). */
    @Transactional(readOnly = true)
    public List<Transaction> list(UUID accountId) {
        requireAccount(accountId);
        return transactionMapper.findByAccountId(accountId);
    }

    private void requireAccount(UUID accountId) {
        if (accountMapper.findById(accountId).isEmpty()) {
            throw new AccountNotFoundException(accountId);
        }
    }
}
