package com.lld.logging;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Shared test helpers: a fixed clock, a hand-moved ticker and small event builders. */
final class TestSupport {

    /** Not instantiable. */
    private TestSupport() {
    }

    /** A clock frozen at 2026-01-01T00:00:00Z so timestamps in assertions are exact. */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    /** A ticker the test moves by hand. */
    static final class FakeTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong(1_000L);

        /** Returns the fake time. */
        @Override
        public long read() {
            return nanos.get();
        }

        /** Moves the fake time forward. */
        void advance(long by) {
            nanos.addAndGet(by);
        }
    }

    /** Builds a plain event with the given message. */
    static LogEvent event(String msg) {
        return new LogEvent(CLOCK.instant(), Level.INFO, "t", "main", msg, null, java.util.Map.of(), java.util.Map.of());
    }

    /** An appender that always throws. */
    static final class Broken implements Appender {
        /** Always fails, like a full disk. */
        @Override
        public void append(LogEvent event) {
            throw new IllegalStateException("disk full");
        }
    }

    /** Spins until the condition is true. Checks the interrupt flag every turn, so a JUnit {@code @Timeout} can end a stuck test instead of hanging on a busy loop. */
    static void spinUntil(BooleanSupplier condition) {
        while (!condition.getAsBoolean()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new AssertionError("gave up waiting: the thread was interrupted by the test timeout");
            }
            Thread.onSpinWait();
        }
    }

    /** Spins until the thread is parked (waiting or timed waiting) or finished, then returns its state. Lets a test know a thread reached its wait without sleeping. */
    static Thread.State settle(Thread t) {
        spinUntil(() -> t.getState() == Thread.State.WAITING || t.getState() == Thread.State.TIMED_WAITING || t.getState() == Thread.State.TERMINATED);
        return t.getState();
    }
}
