package com.lld.workflow;

/**
 * A clock that only moves forward, in nanoseconds. The engine uses it to measure how long a hook took, so tests can move a fake ticker instead of sleeping.
 */
@FunctionalInterface
public interface Ticker {

    /** Returns the current reading in nanoseconds. Only the difference between two readings means anything. */
    long read();

    /** Returns the real clock, {@link System#nanoTime()}, for production use. */
    static Ticker system() {
        return System::nanoTime;
    }
}
