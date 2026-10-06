package com.danielrak.banking.messaging;

import com.danielrak.banking.persistence.OutboxMapper;
import com.danielrak.banking.persistence.OutboxRow;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
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
 * publisher runs at a time, in {@code id} order. A batch stops at the first nack, timeout or error; that row
 * and every later one stay for the next poll. Delivery is at-least-once: a row that timed out, or was sent
 * after one that failed, may already be on the queue and is sent again.
 *
 * <p>A row that fails every time stalls publishing on purpose: order over availability (ADR-0003). Every
 * failure goes through one path: the batch's acked rows are still deleted and committed, then the failure is
 * logged (see {@link FailureLog}) and the next poll backs off (see {@link Backoff}). A poll that ends without
 * a failure, after its commit, resets both.
 */
@Component
public class OutboxPublisher {

    /** The advisory lock every instance's publisher takes: "banking" in ASCII. */
    public static final long LOCK_KEY = 0x62616e6b696e67L;

    /**
     * Wait after a failed poll, doubling per failure up to the cap, reset on success. Each poll would otherwise
     * open a DB transaction and take the lock, and Spring AMQP would log a connection attempt, five times a
     * second during an outage. The wait is checked once per poll, so it rounds up to the next poll.
     */
    static final Duration BACKOFF_INITIAL = Duration.ofMillis(200);
    static final Duration BACKOFF_MAX = Duration.ofSeconds(5);

    /** While a failure lasts, it is logged at WARN again this often (and whenever its kind changes). */
    static final Duration REWARN = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxMapper outboxMapper;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final Duration confirmTimeout;

    // Scheduler thread only.
    private final Backoff backoff = new Backoff(BACKOFF_INITIAL, BACKOFF_MAX);
    private final FailureLog failureLog = new FailureLog(REWARN);

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

    /** Why a batch stopped early. {@code kind} groups repeats of the same failure for {@link FailureLog}. */
    private record Failure(String kind, String reason, Exception cause) {

        /** Shutdown, not a publishing problem: neither logged as a failure nor backed off. */
        static final Failure INTERRUPTED = new Failure("interrupted", "interrupted", null);
    }

    /** What one batch did: whether it was full and fully acked, or why it stopped (null if it didn't). */
    private record BatchResult(boolean full, Failure failure) {
    }

    /**
     * Publishes batches until one is short or fails, so a backlog drains faster than one batch per poll. The
     * failure and success bookkeeping runs here, after each batch's transaction has committed.
     */
    @Scheduled(fixedDelayString = "${banking.outbox.poll-interval}")
    public void poll() {
        if (!backoff.ready(System.nanoTime())) {
            return;   // a recent poll failed: skip the transaction and the lock until the wait passes
        }
        Failure failure;
        try {
            BatchResult result;
            do {
                result = publishBatch();
            } while (result.full());
            failure = result.failure();
        } catch (RuntimeException e) {   // e.g. the database is down; the batch rolled back, nothing was deleted
            failure = new Failure("error:" + e.getClass().getName(), "unexpected " + e.getClass().getSimpleName(), e);
        }

        if (failure == Failure.INTERRUPTED) {
            log.debug("Outbox publishing interrupted, likely shutdown; pending rows stay for the next run");
        } else if (failure != null) {
            failed(failure);
        } else {
            succeeded();
        }
    }

    private BatchResult publishBatch() {
        BatchResult result = transactionTemplate.execute(status -> {
            if (!outboxMapper.tryLock(LOCK_KEY)) {
                return new BatchResult(false, null);   // another instance is publishing
            }
            List<OutboxRow> rows = outboxMapper.findBatch(batchSize);
            if (rows.isEmpty()) {
                return new BatchResult(false, null);
            }
            List<Long> acked = new ArrayList<>(rows.size());
            Failure failure = publish(rows, acked);
            if (!acked.isEmpty()) {
                outboxMapper.deleteByIds(acked);
            }
            return new BatchResult(failure == null && rows.size() == batchSize, failure);
        });
        return Objects.requireNonNull(result);
    }

    /**
     * Sends every row on one channel, then waits for the confirms in order, adding the IDs of the acked prefix
     * to {@code acked}. Stopping at the first failure means a failed row is never overtaken by a later one,
     * which keeps per-balance order. Returns the failure, or null if every row was acked.
     */
    private Failure publish(List<OutboxRow> rows, List<Long> acked) {
        try {
            return rabbitTemplate.invoke(ops -> {
                List<CorrelationData> confirms = new ArrayList<>(rows.size());
                for (OutboxRow row : rows) {
                    CorrelationData correlation = new CorrelationData(row.eventId().toString());
                    ops.send(MessagingConfiguration.EXCHANGE, row.routingKey(), toMessage(row), correlation);
                    confirms.add(correlation);
                }
                long deadline = System.nanoTime() + confirmTimeout.toNanos();
                for (int i = 0; i < rows.size(); i++) {
                    Failure failure = awaitAck(confirms.get(i), rows.get(i), deadline);
                    if (failure != null) {
                        return failure;
                    }
                    acked.add(rows.get(i).id());
                }
                return null;
            });
        } catch (AmqpException e) {
            String type = e.getClass().getSimpleName();
            return new Failure("amqp:" + e.getClass().getName(), "broker error (" + type + "): " + e.getMessage(), e);
        }
    }

    /** Waits for one row's confirm until {@code deadline}; null if acked, else why not. */
    private static Failure awaitAck(CorrelationData correlation, OutboxRow row, long deadline) {
        String event = "event " + row.eventId();
        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            return confirm.ack() ? null : new Failure("nack:" + row.eventId(),
                    event + " nacked: " + Objects.requireNonNullElse(confirm.reason(), "no reason given"), null);
        } catch (TimeoutException e) {
            return new Failure("timeout:" + row.eventId(), event + " not confirmed within the timeout", null);
        } catch (ExecutionException | CancellationException e) {
            return new Failure("confirm-failed:" + row.eventId(), event + " confirm failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Failure.INTERRUPTED;
        }
    }

    private void failed(Failure failure) {
        long now = System.nanoTime();
        Duration wait = backoff.failed(now);
        if (failureLog.failed(failure.kind(), now)) {
            log.warn("Outbox publishing failed, rows stay pending and are retried (failing for {} s, next attempt "
                            + "in {} ms): {}", failureLog.failingFor(now).toSeconds(), wait.toMillis(),
                    failure.reason(), failure.cause());
        } else {
            log.debug("Outbox publishing still failing: {}", failure.reason(), failure.cause());
        }
    }

    private void succeeded() {
        long now = System.nanoTime();
        Duration failedFor = failureLog.failingFor(now);
        backoff.reset();
        if (failureLog.succeeded()) {
            log.info("Outbox publishing recovered after {} s", failedFor.toSeconds());
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
