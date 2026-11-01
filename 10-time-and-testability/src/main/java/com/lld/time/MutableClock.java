package com.lld.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link Clock} that only moves when a test tells it to.
 * <p>The JDK ships {@link Clock#fixed} and {@link Clock#offset}, but both are immutable, so you cannot advance them in the middle of a scenario. This is the usual few-line fake. It is thread-safe, and {@link #withZone} returns a view that shares the same instant.
 * <p>{@link #advance} accepts negative durations on purpose. A real wall clock can step backwards (NTP correction, a VM resumed from a snapshot, an operator fixing the time). Code that measures elapsed time must survive that, which is what {@link Ticker} is for.
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    /**
     * Creates a UTC clock frozen at the start instant.
     */
    public MutableClock(Instant start) {
        this(new AtomicReference<>(start), ZoneOffset.UTC);
    }

    /**
     * Shares the instant holder between views, so a zone view moves together with the original.
     */
    private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    /**
     * Returns the current fake time.
     */
    @Override
    public Instant instant() {
        return now.get();
    }

    /**
     * Returns this clock's zone.
     */
    @Override
    public ZoneId getZone() {
        return zone;
    }

    /**
     * Returns a clock in another zone that shares the same fake time.
     */
    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now, zone);
    }

    /**
     * Moves time by the given amount. A negative amount moves it backwards, on purpose.
     */
    public void advance(Duration by) {
        now.updateAndGet(i -> i.plus(by));
    }

    /**
     * Jumps straight to the given time.
     */
    public void setInstant(Instant instant) {
        now.set(instant);
    }
}
