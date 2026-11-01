package com.lld.time;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A test {@link Ticker} that only moves when a test says so. Unlike {@link MutableClock}, it refuses to go backwards, because monotonic means monotonic.
 */
public final class ManualTicker implements Ticker {

    private final AtomicLong nanos = new AtomicLong();

    /**
     * Returns the current virtual nanoseconds, starting at zero.
     */
    @Override
    public long read() {
        return nanos.get();
    }

    /**
     * Moves the ticker forward. Throws {@code IllegalArgumentException} for a negative duration.
     */
    public void advance(Duration by) {
        if (by.isNegative()) {
            throw new IllegalArgumentException("a monotonic ticker cannot go backwards: " + by);
        }
        nanos.addAndGet(by.toNanos());
    }
}
