package com.lld.connpool;

/**
 * A clock that only moves forward, counted in nanoseconds.
 *
 * <p>The pool uses it to work out how long a borrower has waited, how long a connection was held and how long it sat idle. Tests pass in a fake one and move it by hand, so no test ever sleeps.
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
