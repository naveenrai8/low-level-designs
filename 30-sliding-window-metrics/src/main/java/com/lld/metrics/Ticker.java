package com.lld.metrics;

/**
 * A clock that only moves forward, counted in nanoseconds.
 *
 * <p>A sliding window cares about how much time has passed, never about the date. Passing the clock in is what lets every test move time by hand instead of sleeping.
 *
 * <p>The reading can be negative and its starting point is arbitrary (that is allowed for {@link System#nanoTime()}), so only differences mean anything. The metric code uses floor division so negative readings still work.
 */
@FunctionalInterface
public interface Ticker {

    /** Returns the current reading in nanoseconds. */
    long read();

    /** Returns the real clock, {@link System#nanoTime()}. This is the only place production code touches real time. */
    static Ticker system() {
        return System::nanoTime;
    }
}
