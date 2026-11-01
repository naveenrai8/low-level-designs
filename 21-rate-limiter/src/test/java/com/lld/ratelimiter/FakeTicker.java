package com.lld.ratelimiter;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** A clock that tests move by hand, so they move time instead of sleeping. Safe to use from many threads. */
final class FakeTicker implements Ticker {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L); // non-zero origin on purpose

    /** Returns the current fake time in nanoseconds. */
    @Override
    public long read() {
        return nanos.get();
    }

    /** Moves the fake time forward by {@code d}. */
    void advance(Duration d) {
        nanos.addAndGet(d.toNanos());
    }

    /** Moves the fake time forward by {@code millis} milliseconds. */
    void advanceMillis(long millis) {
        advance(Duration.ofMillis(millis));
    }
}
