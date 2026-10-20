package com.lld.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link Clock} that only moves when a test tells it to.
 *
 * <p>The JDK ships {@link Clock#fixed} and {@link Clock#offset}, but both are immutable: you can't
 * advance them in the middle of a scenario. This is the usual few-line fake. It's thread-safe, and
 * {@link #withZone} returns a view that shares the same instant.
 *
 * <p>{@link #advance} accepts negative durations on purpose: a real wall clock <em>can</em> step
 * backwards (NTP correction, a VM resumed from snapshot, an operator fixing the time). Code that
 * measures elapsed time must survive that, which is what {@link Ticker} is for.
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(new AtomicReference<>(start), ZoneOffset.UTC);
    }

    private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now, zone);
    }

    public void advance(Duration by) {
        now.updateAndGet(i -> i.plus(by));
    }

    public void setInstant(Instant instant) {
        now.set(instant);
    }
}
