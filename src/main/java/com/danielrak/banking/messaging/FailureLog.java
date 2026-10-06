package com.danielrak.banking.messaging;

import java.time.Duration;

/**
 * Decides how loudly {@link OutboxPublisher} reports a failure: WARN when a failure starts, when its kind
 * changes (e.g. from "broker unreachable" to "event X nacked"), and every {@code rewarn} while it lasts;
 * DEBUG otherwise. So a stuck row or a new outage is never reported at DEBUG only, and a long outage doesn't
 * log on every poll. Times are {@link System#nanoTime()} values, passed in so the policy can be tested
 * without a clock. Not thread-safe: the scheduler thread only.
 */
final class FailureLog {

    private final Duration rewarn;
    private String kind;   // null while healthy
    private long sinceNanos;
    private long lastWarnNanos;

    FailureLog(Duration rewarn) {
        this.rewarn = rewarn;
    }

    /** Records a failure of {@code kind} at {@code nowNanos}; true if it should be logged at WARN. */
    boolean failed(String kind, long nowNanos) {
        boolean warn = !kind.equals(this.kind) || nowNanos - lastWarnNanos >= rewarn.toNanos();
        if (this.kind == null) {
            sinceNanos = nowNanos;
        }
        this.kind = kind;
        if (warn) {
            lastWarnNanos = nowNanos;
        }
        return warn;
    }

    /** How long publishing has been failing at {@code nowNanos}; zero while healthy. */
    Duration failingFor(long nowNanos) {
        return kind == null ? Duration.ZERO : Duration.ofNanos(nowNanos - sinceNanos);
    }

    /** Records a success; true if it ends a failure, so the caller can log the recovery. */
    boolean succeeded() {
        boolean recovered = kind != null;
        kind = null;
        return recovered;
    }
}
