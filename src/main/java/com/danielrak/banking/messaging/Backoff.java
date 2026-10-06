package com.danielrak.banking.messaging;

import java.time.Duration;

/**
 * Exponential backoff for {@link OutboxPublisher} after a failed poll (broker unreachable, nack, confirm
 * timeout, database error): each failure doubles the wait from {@code initial} up to {@code max}, and a
 * success resets it. Times are {@link System#nanoTime()}
 * values, passed in so the policy can be tested without a clock. Not thread-safe: the scheduler thread only.
 */
final class Backoff {

    private final Duration initial;
    private final Duration max;
    private Duration current = Duration.ZERO;
    private long nextAttemptNanos;

    Backoff(Duration initial, Duration max) {
        this.initial = initial;
        this.max = max;
    }

    /** Whether an attempt may run at {@code nowNanos}: always, unless a failure's wait hasn't passed. */
    boolean ready(long nowNanos) {
        return current.isZero() || nowNanos - nextAttemptNanos >= 0;
    }

    /** Records a failure at {@code nowNanos} and returns the wait before the next attempt. */
    Duration failed(long nowNanos) {
        current = current.isZero() ? initial : min(current.multipliedBy(2), max);
        nextAttemptNanos = nowNanos + current.toNanos();
        return current;
    }

    void reset() {
        current = Duration.ZERO;
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
