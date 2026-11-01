package com.lld.ledger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock that only moves when a test says so, so hold expiry needs no waiting. */
final class TestClock extends Clock {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

    /**
     * Moves the clock forward.
     *
     * @param d how far to move
     */
    void advance(Duration d) {
        now.updateAndGet(t -> t.plus(d));
    }

    /** Always UTC; the test clock ignores zones. */
    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /** Returns this clock, because zones do not matter here. */
    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    /** Returns the manually controlled current time. */
    @Override
    public Instant instant() {
        return now.get();
    }
}
