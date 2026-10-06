package com.danielrak.banking.persistence;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OutboxMapper {

    @Insert("INSERT INTO outbox_event (event_id, routing_key, payload) "
            + "VALUES (#{eventId}, #{routingKey}, CAST(#{payload} AS json))")
    void insert(UUID eventId, String routingKey, String payload);

    /** Takes the transaction-scoped advisory lock {@code key} if it is free; never waits (ADR-0003). */
    @Select("SELECT pg_try_advisory_xact_lock(#{key})")
    boolean tryLock(long key);

    @Select("SELECT id, event_id, routing_key, payload FROM outbox_event ORDER BY id LIMIT #{limit}")
    List<OutboxRow> findBatch(int limit);

    /** Deletes by ID, never by {@code id <= max}: a row with a lower ID can commit later. */
    @Delete("""
            <script>
            DELETE FROM outbox_event WHERE id IN
            <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    void deleteByIds(List<Long> ids);
}
