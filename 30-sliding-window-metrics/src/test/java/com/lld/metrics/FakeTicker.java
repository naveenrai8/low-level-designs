package com.lld.metrics;

import java.util.concurrent.atomic.AtomicLong;

/** A clock that tests move by hand, so they never sleep. Safe to use from many threads. */
final class FakeTicker implements Ticker {

    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong nanos;

    /** Starts the clock at {@code startNanos}; tests pass 0, or a negative number to prove negative readings work. */
    FakeTicker(long startNanos) {
        this.nanos = new AtomicLong(startNanos);
    }

    /** Starts the clock at 0, so "t = 3 s" in a test really is 3 seconds. */
    FakeTicker() {
        this(0);
    }

    /** Returns the current fake reading. */
    @Override
    public long read() {
        return nanos.get();
    }

    /** Moves the clock to exactly {@code seconds} seconds past zero. */
    void setSeconds(long seconds) {
        nanos.set(seconds * SECOND);
    }

    /** Moves the clock forward by {@code nanos} nanoseconds (may be negative, to step just before a boundary). */
    void advanceNanos(long nanos) {
        this.nanos.addAndGet(nanos);
    }

    /** Moves the clock forward by {@code millis} milliseconds. */
    void advanceMillis(long millis) {
        nanos.addAndGet(millis * 1_000_000L);
    }

    /** Moves the clock forward by {@code seconds} seconds. */
    void advanceSeconds(long seconds) {
        nanos.addAndGet(seconds * SECOND);
    }
}
