package com.danielrak.banking.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.danielrak.banking.domain.Currency;
import com.danielrak.banking.domain.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the design.md §5 wire format byte for byte. The outbox stores this text as {@code json} and the
 * publisher sends it unchanged, so a change here is a change to the event contract.
 */
class EventJsonTest {

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-00000000000e");
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID TRANSACTION_ID = UUID.fromString("00000000-0000-0000-0000-00000000000f");
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-02T12:00:00Z");

    @Test
    void balanceUpdatedEnvelope() {
        EventEnvelope envelope = new EventEnvelope(EVENT_ID, EventTypes.BALANCE_UPDATED, OCCURRED_AT, ACCOUNT_ID,
                new BalanceUpdatedData(ACCOUNT_ID, Currency.EUR, new BigDecimal("10.50"), TRANSACTION_ID));

        assertThat(EventJson.MAPPER.writeValueAsString(envelope)).isEqualTo("""
                {"eventId":"00000000-0000-0000-0000-00000000000e","eventType":"balance.updated",\
                "occurredAt":"2026-10-02T12:00:00Z","accountId":"00000000-0000-0000-0000-00000000000a",\
                "data":{"accountId":"00000000-0000-0000-0000-00000000000a","currency":"EUR",\
                "availableAmount":10.50,"transactionId":"00000000-0000-0000-0000-00000000000f"}}""");
    }

    @Test
    void transactionCreatedDataKeepsScaleAndDeclarationOrder() {
        EventEnvelope envelope = new EventEnvelope(EVENT_ID, EventTypes.TRANSACTION_CREATED,
                Instant.parse("2026-10-02T12:00:00.123456Z"), ACCOUNT_ID,
                new TransactionCreatedData(TRANSACTION_ID, ACCOUNT_ID, new BigDecimal("100.00"), Currency.SEK,
                        Direction.OUT, "Rent", new BigDecimal("0.00")));

        assertThat(EventJson.MAPPER.writeValueAsString(envelope)).isEqualTo("""
                {"eventId":"00000000-0000-0000-0000-00000000000e","eventType":"transaction.created",\
                "occurredAt":"2026-10-02T12:00:00.123456Z","accountId":"00000000-0000-0000-0000-00000000000a",\
                "data":{"transactionId":"00000000-0000-0000-0000-00000000000f",\
                "accountId":"00000000-0000-0000-0000-00000000000a","amount":100.00,"currency":"SEK",\
                "direction":"OUT","description":"Rent","balanceAfter":0.00}}""");
    }
}
