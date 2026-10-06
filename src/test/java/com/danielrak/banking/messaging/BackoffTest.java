package com.danielrak.banking.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The outbox poller's broker backoff: 200 ms doubling to a 5 s cap, reset on success. */
class BackoffTest {

    private static final long MS = 1_000_000L;

    private final Backoff backoff = new Backoff(OutboxPublisher.BACKOFF_INITIAL, OutboxPublisher.BACKOFF_MAX);

    @Test
    void readyUntilTheFirstFailure() {
        assertThat(backoff.ready(0)).isTrue();
        assertThat(backoff.ready(Long.MAX_VALUE)).isTrue();
    }

    @Test
    void doublesFrom200MsToA5sCap() {
        long now = 0;
        long[] expectedMs = {200, 400, 800, 1600, 3200, 5000, 5000};
        for (long expected : expectedMs) {
            assertThat(backoff.failed(now)).isEqualTo(Duration.ofMillis(expected));
            assertThat(backoff.ready(now + (expected - 1) * MS)).as("still waiting before %d ms", expected).isFalse();
            assertThat(backoff.ready(now + expected * MS)).as("ready at %d ms", expected).isTrue();
            now += expected * MS;
        }
    }

    @Test
    void successResetsToTheInitialWait() {
        backoff.failed(0);
        backoff.failed(0);
        backoff.reset();

        assertThat(backoff.ready(0)).isTrue();
        assertThat(backoff.failed(0)).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    void comparesNanoTimesSafelyAcrossOverflow() {
        long nearOverflow = Long.MAX_VALUE - 100 * MS;
        backoff.failed(nearOverflow);

        assertThat(backoff.ready(nearOverflow + 199 * MS)).isFalse();   // wraps negative
        assertThat(backoff.ready(nearOverflow + 200 * MS)).isTrue();
    }
}
