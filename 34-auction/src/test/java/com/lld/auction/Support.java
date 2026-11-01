package com.lld.auction;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared test helpers: a clock the test moves by hand, and common constants.
 */
final class Support {

    /** The fixed start of every test timeline. */
    static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");

    /** Not instantiable. */
    private Support() {
    }

    /** A clock that only moves when the test says so, so a test can stand on an exact nanosecond. */
    static final class TestClock extends Clock {

        private final AtomicReference<Instant> now = new AtomicReference<>(T0);

        /** Jumps to an exact instant. */
        void set(Instant t) {
            now.set(t);
        }

        /** Moves the clock forward by the given amount. */
        void advance(Duration d) {
            now.updateAndGet(t -> t.plus(d));
        }

        /** Returns the zone, always UTC. */
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /** Not needed by the tests, so it returns the same clock. */
        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        /** Returns the instant the test last set. */
        @Override
        public Instant instant() {
            return now.get();
        }
    }

    /** The standard item: seller "sam", no reserve, increment 100. */
    static Item item() {
        return new Item("lamp", "sam", 0, 100);
    }

    /** The same item with a reserve of 1000. */
    static Item reserved() {
        return new Item("lamp", "sam", 1000, 100);
    }

    /** Waits (by spinning, never sleeping) until the auction has the given number of threads queued on its lock; gives up after a bounded number of spins. */
    static void awaitWaiting(LockedAuction a, int n) {
        for (long i = 0; i < 2_000_000_000L; i++) {
            if (a.waiting() == n) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("no thread queued on the lock");
    }
}
