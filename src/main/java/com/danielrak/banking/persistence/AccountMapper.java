package com.danielrak.banking.persistence;

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
}
