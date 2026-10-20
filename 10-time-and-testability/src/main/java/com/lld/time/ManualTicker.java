package com.lld.time;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** A test {@link Ticker}. Unlike {@link MutableClock}, it refuses to go backwards: monotonic means monotonic. */
public final class ManualTicker implements Ticker {

    private final AtomicLong nanos = new AtomicLong();

    @Override
    public long read() {
        return nanos.get();
    }

    public void advance(Duration by) {
        if (by.isNegative()) {
            throw new IllegalArgumentException("a monotonic ticker cannot go backwards: " + by);
        }
        nanos.addAndGet(by.toNanos());
    }
}
