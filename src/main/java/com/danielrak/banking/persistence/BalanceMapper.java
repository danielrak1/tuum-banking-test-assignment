package com.danielrak.banking.persistence;

import com.danielrak.banking.domain.Balance;
import com.danielrak.banking.domain.Currency;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** The only place balances are written. Amounts change only through {@link #applyDelta} (ADR-0002). */
@Mapper
public interface BalanceMapper {

    /** Opens a balance at the column default of 0. */
    @Insert("INSERT INTO balance (account_id, currency) VALUES (#{accountId}, #{currency})")
    void insert(UUID accountId, Currency currency);

    @Select("SELECT currency, available_amount FROM balance WHERE account_id = #{accountId} ORDER BY currency")
    List<Balance> findByAccountId(UUID accountId);

    /**
     * Adds {@code delta} (negative for OUT) only if the result stays ≥ 0, and returns the new amount;
     * empty if the condition failed. A concurrent writer waits on the row lock, then Postgres
     * re-checks the {@code WHERE} against the committed row, so the balance can't go negative (ADR-0002).
     * It's a {@code @Select} so {@code RETURNING} maps to the result. The local cache is
     * statement-scoped ({@code application.yml}), so a repeat call always runs the update.
     */
    @Select("UPDATE balance SET available_amount = available_amount + #{delta} "
            + "WHERE account_id = #{accountId} AND currency = #{currency} AND available_amount + #{delta} >= 0 "
            + "RETURNING available_amount")
    Optional<BigDecimal> applyDelta(UUID accountId, Currency currency, BigDecimal delta);
}
