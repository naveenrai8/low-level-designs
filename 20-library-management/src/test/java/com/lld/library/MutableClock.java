package com.lld.library;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the test moves by hand, so no test ever waits for real time. */
final class MutableClock extends Clock {

    private volatile Instant now;

    /** Starts the clock at the given instant. */
    MutableClock(Instant start) {
        this.now = start;
    }

    /** Moves the clock forward (or backward, if negative) by the given amount. */
    void advance(Duration d) {
        now = now.plus(d);
    }

    /** {@inheritDoc} */
    @Override
    public Instant instant() {
        return now;
    }

    /** {@inheritDoc} */
    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /** {@inheritDoc} */
    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
