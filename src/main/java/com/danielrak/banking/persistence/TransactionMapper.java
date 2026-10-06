package com.danielrak.banking.persistence;

import com.danielrak.banking.domain.Transaction;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TransactionMapper {

    /** {@code seq} comes from its bigserial; insert after the balance update, while its row lock is held (design.md §4). */
    @Insert("INSERT INTO account_transaction (id, account_id, currency, direction, amount, description, balance_after) "
            + "VALUES (#{id}, #{accountId}, #{currency}, #{direction}, #{amount}, #{description}, #{balanceAfter})")
    void insert(Transaction transaction);

    @Select("SELECT id, account_id, amount, currency, direction, description, balance_after "
            + "FROM account_transaction WHERE account_id = #{accountId} ORDER BY seq")
    List<Transaction> findByAccountId(UUID accountId);
}
