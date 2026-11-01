package com.lld.editor;

/**
 * A clock that only moves forward, counted in nanoseconds.
 *
 * <p>The editor only needs to know how long the user paused between two keystrokes, so it needs elapsed time, not the date. Passing the clock in lets tests move a fake ticker instead of sleeping.
 */
@FunctionalInterface
public interface Ticker {

    /** Returns the current reading in nanoseconds. The starting point is arbitrary, so only differences mean anything. */
    long read();

    /** Returns the real clock, {@link System#nanoTime()}, for production use. */
    static Ticker system() {
        return System::nanoTime;
    }
}
