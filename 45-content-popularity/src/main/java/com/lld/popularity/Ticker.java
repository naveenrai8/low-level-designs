package com.lld.popularity;

/**
 * A clock that only moves forward, counted in nanoseconds.
 *
 * <p>Every event in this topic is stamped with this clock, so tests move a fake ticker instead of sleeping. Only differences between two readings mean anything.
 */
@FunctionalInterface
public interface Ticker {

    /** Returns the current reading in nanoseconds. */
    long read();

    /** Returns the real clock, {@link System#nanoTime()}, for production use. */
    static Ticker system() {
        return System::nanoTime;
    }
}
