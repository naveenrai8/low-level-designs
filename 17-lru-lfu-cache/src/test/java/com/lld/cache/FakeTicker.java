package com.lld.cache;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** Manually advanced time source: tests move time instead of sleeping. Thread-safe. */
final class FakeTicker implements Ticker {

    private final AtomicLong nanos;

    /** Starts at a non-zero time, so code that wrongly assumes zero is caught. */
    FakeTicker() {
        this(1_000_000_000L);                         // non-zero origin on purpose
    }

    /** Starts at the given time. Tests pass a value near Long.MAX_VALUE to force overflow. */
    FakeTicker(long origin) {
        this.nanos = new AtomicLong(origin);
    }

    /** Returns the current fake time in nanoseconds. */
    @Override
    public long read() {
        return nanos.get();
    }

    /** Moves time forward by a Duration. */
    void advance(Duration d) {
        nanos.addAndGet(d.toNanos());
    }

    /** Moves time forward by a number of nanoseconds. */
    void advanceNanos(long n) {
        nanos.addAndGet(n);
    }
}
