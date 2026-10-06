package com.danielrak.banking.persistence;

import com.danielrak.banking.domain.Currency;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AccountMapper {

    @Insert("INSERT INTO account (id, customer_id, country) VALUES (#{id}, #{customerId}, #{country})")
    void insert(UUID id, String customerId, String country);

    @Select("SELECT id, customer_id, country FROM account WHERE id = #{id}")
    Optional<AccountRow> findById(UUID id);

    @Select("SELECT EXISTS (SELECT 1 FROM account WHERE id = #{id})")
    boolean exists(UUID id);

    /** Both checks a transaction needs, in one round trip. */
    @Select("SELECT EXISTS (SELECT 1 FROM account WHERE id = #{accountId}) AS account, "
            + "EXISTS (SELECT 1 FROM balance WHERE account_id = #{accountId} AND currency = #{currency}) AS balance")
    Existence findExistence(UUID accountId, Currency currency);
}
