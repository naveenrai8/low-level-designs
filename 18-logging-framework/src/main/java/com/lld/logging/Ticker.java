package com.lld.logging;

/**
 * A clock that only moves forward, counted in nanoseconds. Used to measure "how long have I waited" for the blocking drop policy.
 *
 * <p>Wall time ({@code Clock}) stamps the log lines. This ticker measures elapsed time. Tests pass a fake one, so no test ever sleeps.
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
