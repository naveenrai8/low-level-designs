package com.lld.scheduler;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** A clock that tests move by hand, so no test ever sleeps. Safe to read from many threads. */
final class FakeTicker implements Ticker {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L); // non-zero origin on purpose

    /** Returns the current fake time in nanoseconds. */
    @Override
    public long read() {
        return nanos.get();
    }

    /** Moves the fake time forward by {@code millis} milliseconds. */
    void advanceMillis(long millis) {
        nanos.addAndGet(Duration.ofMillis(millis).toNanos());
    }

    /** Returns the fake time as milliseconds since the starting point, which is how the timeline tables in the README count. */
    long elapsedMillis() {
        return (nanos.get() - 1_000_000_000L) / 1_000_000L;
    }
}
