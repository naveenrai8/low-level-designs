package com.lld.leaderboard;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests can move by hand, so no test ever waits for real time. */
final class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant now;

    /** Creates a clock frozen at {@code start} in UTC. */
    MutableClock(Instant start) {
        this(start, ZoneOffset.UTC);
    }

    /** Creates a clock frozen at {@code start} reporting the given zone. */
    MutableClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    /** Moves the clock forward by {@code d}. */
    void advance(Duration d) {
        now = now.plus(d);
    }

    /** Jumps the clock to {@code t}. */
    void set(Instant t) {
        now = t;
    }

    /** Returns the zone this clock reports. */
    @Override
    public ZoneId getZone() {
        return zone;
    }

    /** Returns a copy of this clock that reports another zone. */
    @Override
    public Clock withZone(ZoneId z) {
        return new MutableClock(now, z);
    }

    /** Returns the current fake instant. */
    @Override
    public Instant instant() {
        return now;
    }
}
