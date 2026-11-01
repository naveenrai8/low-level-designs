package com.lld.blockingqueue;

/**
 * A clock that only moves forward, counted in nanoseconds. It is used for timeouts ("wait at most 2 seconds").
 *
 * <p>Passing the clock in lets tests decide how much time has passed, so no test ever has to sleep.
 */
@FunctionalInterface
public interface Ticker {

    /** Returns the current reading in nanoseconds. The starting point is arbitrary, so only the difference between two readings means anything. */
    long read();

    /** Returns the real clock, {@link System#nanoTime()}, for production use. */
    static Ticker system() {
        return System::nanoTime;
    }
}
