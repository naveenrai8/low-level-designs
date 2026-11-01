package com.lld.resilience;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** A hand-moved clock for tests: time passes only when the test calls {@link #advance}. */
final class FakeTicker implements Ticker {

    private final AtomicLong now = new AtomicLong(1_000);

    /** Returns the current fake reading. */
    @Override
    public long read() {
        return now.get();
    }

    /** Moves the clock forward by the given number of nanoseconds. */
    void advanceNanos(long nanos) {
        now.addAndGet(nanos);
    }

    /** Moves the clock forward by the given duration. */
    void advance(Duration d) {
        advanceNanos(d.toNanos());
    }
}
