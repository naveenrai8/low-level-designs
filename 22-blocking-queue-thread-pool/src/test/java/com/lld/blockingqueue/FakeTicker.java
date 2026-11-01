package com.lld.blockingqueue;

import java.util.concurrent.atomic.AtomicLong;

/** A clock for tests. Every read moves it forward by a fixed step, so a timeout expires after a known number of reads and no test ever waits for real time. */
final class FakeTicker implements Ticker {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L); // non-zero origin on purpose
    private volatile long stepNanos;

    /** Creates a ticker that moves forward by {@code stepNanos} on every read. Use 0 for a frozen clock. */
    FakeTicker(long stepNanos) {
        this.stepNanos = stepNanos;
    }

    /** Freezes the clock from now on: later reads all return the same time, so no timeout can expire. */
    void freeze() {
        stepNanos = 0;
    }

    /** Returns the current fake time, then moves it forward by the step. */
    @Override
    public long read() {
        return nanos.getAndAdd(stepNanos);
    }
}
