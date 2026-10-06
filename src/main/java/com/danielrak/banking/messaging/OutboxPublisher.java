package com.danielrak.banking.messaging;

import com.danielrak.banking.persistence.OutboxMapper;
import com.danielrak.banking.persistence.OutboxRow;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes {@code outbox_event} rows to {@code banking.events} and deletes them once the broker confirms
 * (ADR-0003). Each batch runs in one transaction that holds an advisory lock, so across instances only one
 * publisher runs at a time, in {@code id} order. A batch stops at the first nack, timeout or connection
 * failure; that row and every later one stay for the next poll. Delivery is at-least-once: a row that
 * timed out, or was sent after one that failed, may already be on the queue and is sent again.
 */
@Component
public class OutboxPublisher {

    /** The advisory lock every instance's publisher takes: "banking" in ASCII. */
    public static final long LOCK_KEY = 0x62616e6b696e67L;

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxMapper outboxMapper;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final Duration confirmTimeout;

    /** Whether the last batch failed, so an outage logs one WARN rather than one per poll. Scheduler thread only. */
    private boolean failing;

    public OutboxPublisher(OutboxMapper outboxMapper, RabbitTemplate rabbitTemplate,
                           TransactionTemplate transactionTemplate,
                           @Value("${banking.outbox.batch-size}") int batchSize,
                           @Value("${banking.outbox.confirm-timeout}") Duration confirmTimeout) {
        this.outboxMapper = outboxMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.transactionTemplate = transactionTemplate;
        this.batchSize = batchSize;
        this.confirmTimeout = confirmTimeout;
    }

    /** Publishes batches until one is short or fails, so a backlog drains faster than one batch per poll. */
    @Scheduled(fixedDelayString = "${banking.outbox.poll-interval}")
    public void poll() {
        while (publishBatch()) {
            // the batch was full and fully acked: there may be more
        }
    }

    /** Publishes one batch; true if it was full and every row was acked. */
    private boolean publishBatch() {
        Boolean full = transactionTemplate.execute(status -> {
            if (!outboxMapper.tryLock(LOCK_KEY)) {
                return false;   // another instance is publishing
            }
            List<OutboxRow> rows = outboxMapper.findBatch(batchSize);
            if (rows.isEmpty()) {
                return false;
            }
            List<Long> acked = publish(rows);
            if (!acked.isEmpty()) {
                outboxMapper.deleteByIds(acked);
            }
            return acked.size() == batchSize;
        });
        return Boolean.TRUE.equals(full);
    }

    /**
     * Sends every row on one channel, then waits for the confirms in order, and returns the IDs of the acked
     * prefix. Stopping at the first failure means a failed row is never overtaken by a later one, which keeps
     * per-balance order.
     */
    private List<Long> publish(List<OutboxRow> rows) {
        List<Long> acked = new ArrayList<>(rows.size());
        try {
            rabbitTemplate.invoke(ops -> {
                List<CorrelationData> confirms = new ArrayList<>(rows.size());
                for (OutboxRow row : rows) {
                    CorrelationData correlation = new CorrelationData(row.eventId().toString());
                    ops.send(MessagingConfiguration.EXCHANGE, row.routingKey(), toMessage(row), correlation);
                    confirms.add(correlation);
                }
                long deadline = System.nanoTime() + confirmTimeout.toNanos();
                for (int i = 0; i < rows.size(); i++) {
                    String failure = awaitAck(confirms.get(i), deadline);
                    if (failure != null) {
                        failed("event " + rows.get(i).eventId() + " " + failure, null);
                        return null;
                    }
                    acked.add(rows.get(i).id());
                }
                return null;
            });
        } catch (AmqpException e) {
            failed("broker unavailable", e);
            return acked;
        }
        if (acked.size() == rows.size() && failing) {
            failing = false;
            log.info("Outbox publishing recovered");
        }
        return acked;
    }

    /** Waits for one confirm until {@code deadline}; null if acked, else why not. */
    private static String awaitAck(CorrelationData correlation, long deadline) {
        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            return confirm.ack() ? null : "nacked: " + confirm.reason();
        } catch (TimeoutException e) {
            return "not confirmed within the timeout";
        } catch (ExecutionException e) {
            return "confirm failed: " + e.getCause();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted while waiting for the confirm";
        }
    }

    private void failed(String reason, Exception cause) {
        if (failing) {
            log.debug("Outbox publishing still failing: {}", reason, cause);
        } else {
            failing = true;
            log.warn("Outbox publishing failed, rows stay pending and are retried: {}", reason, cause);
        }
    }

    private static Message toMessage(OutboxRow row) {
        return MessageBuilder.withBody(row.payload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setMessageId(row.eventId().toString())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
    }
}
