package com.danielrak.banking.persistence;

import com.danielrak.banking.domain.Balance;
import com.danielrak.banking.domain.Currency;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** The only place balances are written. Amounts change only through {@code applyDelta} (ADR-0002, task 3). */
@Mapper
public interface BalanceMapper {

    /** Opens a balance at the column default of 0. */
    @Insert("INSERT INTO balance (account_id, currency) VALUES (#{accountId}, #{currency})")
    void insert(UUID accountId, Currency currency);

    @Select("SELECT currency, available_amount FROM balance WHERE account_id = #{accountId} ORDER BY currency")
    List<Balance> findByAccountId(UUID accountId);
}
