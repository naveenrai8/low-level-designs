package com.lld.idgen;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** A wall clock that tests move by hand, forwards or backwards. Safe to use from many threads. */
final class FakeClock extends Clock {

    private final AtomicLong millis;

    /** Creates a clock frozen at {@code startMillis} (Unix milliseconds). */
    FakeClock(long startMillis) {
        this.millis = new AtomicLong(startMillis);
    }

    /** Returns the current fake time in Unix milliseconds. */
    @Override
    public long millis() {
        return millis.get();
    }

    /** Returns the current fake time as an Instant. */
    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis.get());
    }

    /** Always UTC; zones do not matter to the generators. */
    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /** Zones are ignored, so this returns the same clock. */
    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    /** Moves the clock by {@code delta} milliseconds; a negative value steps it back. */
    void advance(long delta) {
        millis.addAndGet(delta);
    }

    /** Sets the clock to an exact value. */
    void set(long value) {
        millis.set(value);
    }

    /** Returns a waiter that counts its calls and moves this clock forward 1 ms on every call, like real time passing. */
    CountingWaiter advancingWaiter() {
        return new CountingWaiter(this);
    }

    /** A {@link Waiter} that records how often it was asked to wait and advances the fake clock by 1 ms each time. */
    static final class CountingWaiter implements Waiter {
        private final FakeClock clock;
        private final AtomicLong calls = new AtomicLong();

        /** Creates a waiter tied to {@code clock}. */
        CountingWaiter(FakeClock clock) {
            this.clock = clock;
        }

        /** Counts the call and moves the clock 1 ms forward. */
        @Override
        public void pause() {
            calls.incrementAndGet();
            clock.advance(1);
        }

        /** Returns how many times the generator had to wait. */
        long calls() {
            return calls.get();
        }
    }
}
