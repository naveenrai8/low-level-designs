package com.lld.booking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock that only moves when a test says so. Topic 10 explains why. */
final class TestClock extends Clock {

    static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);

    /** Returns the fake current time. */
    @Override
    public Instant instant() {
        return now.get();
    }

    /** Returns UTC. */
    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /** Zones are irrelevant here, so the same clock is returned. */
    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    /** Moves the clock forward by the given duration. */
    void advance(Duration d) {
        now.updateAndGet(i -> i.plus(d));
    }
}
