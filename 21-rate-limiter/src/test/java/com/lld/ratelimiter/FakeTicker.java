package com.lld.ratelimiter;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** Manually advanced time source: tests move time instead of sleeping. Thread-safe. */
final class FakeTicker implements Ticker {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L); // non-zero origin on purpose

    @Override
    public long read() {
        return nanos.get();
    }

    void advance(Duration d) {
        nanos.addAndGet(d.toNanos());
    }

    void advanceMillis(long millis) {
        advance(Duration.ofMillis(millis));
    }
}
