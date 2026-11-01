package com.lld.kvstore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A test clock that only moves when a test calls {@link #advance(Duration)}, so no test ever sleeps. See Topic 10.
 */
final class ManualClock extends Clock {

    private final AtomicLong ms = new AtomicLong(1_000_000L);

    /** Moves virtual time forward by the given duration. */
    void advance(Duration d) {
        ms.addAndGet(d.toMillis());
    }

    /** Returns the zone (always UTC; it never matters here). */
    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /** Ignores the zone and returns this same clock. */
    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    /** Returns the current virtual time as an Instant. */
    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(ms.get());
    }

    /** Returns the current virtual time in epoch milliseconds. */
    @Override
    public long millis() {
        return ms.get();
    }
}
