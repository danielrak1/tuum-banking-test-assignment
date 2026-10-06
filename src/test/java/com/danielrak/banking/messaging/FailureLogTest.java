package com.danielrak.banking.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** When the outbox poller logs a failure at WARN rather than DEBUG (SF-1 of the task 4 review). */
class FailureLogTest {

    private static final long S = 1_000_000_000L;

    private final FailureLog failureLog = new FailureLog(OutboxPublisher.REWARN);

    @Test
    void warnsWhenAFailureStartsThenDebugForRepeatsOfTheSameKind() {
        assertThat(failureLog.failed("amqp", 0)).isTrue();
        assertThat(failureLog.failed("amqp", 1 * S)).isFalse();
        assertThat(failureLog.failed("amqp", 59 * S)).isFalse();
    }

    @Test
    void warnsAgainEveryRewarnIntervalWhileTheSameFailureLasts() {
        failureLog.failed("timeout:e1", 0);

        assertThat(failureLog.failed("timeout:e1", 60 * S)).isTrue();
        assertThat(failureLog.failed("timeout:e1", 61 * S)).isFalse();
        assertThat(failureLog.failed("timeout:e1", 120 * S)).isTrue();
    }

    @Test
    void warnsAgainWhenTheKindChanges() {
        failureLog.failed("amqp", 0);

        assertThat(failureLog.failed("nack:e1", 1 * S)).as("broker back, but a row is nacked").isTrue();
        assertThat(failureLog.failed("nack:e2", 2 * S)).as("a different stuck row").isTrue();
        assertThat(failureLog.failed("nack:e2", 3 * S)).isFalse();
    }

    @Test
    void measuresTheFailureFromItsStartAcrossKindChanges() {
        assertThat(failureLog.failingFor(5 * S)).isEqualTo(Duration.ZERO);
        failureLog.failed("amqp", 10 * S);
        failureLog.failed("nack:e1", 30 * S);

        assertThat(failureLog.failingFor(45 * S)).isEqualTo(Duration.ofSeconds(35));
    }

    @Test
    void successEndsTheFailureSoTheNextOneWarnsAndIsTimedAfresh() {
        assertThat(failureLog.succeeded()).as("nothing to recover from").isFalse();
        failureLog.failed("amqp", 0);
        failureLog.failed("amqp", 1 * S);

        assertThat(failureLog.succeeded()).as("recovered").isTrue();
        assertThat(failureLog.failingFor(2 * S)).isEqualTo(Duration.ZERO);
        assertThat(failureLog.failed("amqp", 3 * S)).as("a new outage warns again").isTrue();
        assertThat(failureLog.failingFor(4 * S)).isEqualTo(Duration.ofSeconds(1));
    }
}
