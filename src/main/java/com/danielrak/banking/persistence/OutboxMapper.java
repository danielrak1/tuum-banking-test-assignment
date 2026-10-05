package com.danielrak.banking.persistence;

import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OutboxMapper {

    @Insert("INSERT INTO outbox_event (event_id, routing_key, payload) "
            + "VALUES (#{eventId}, #{routingKey}, CAST(#{payload} AS jsonb))")
    void insert(UUID eventId, String routingKey, String payload);
}
