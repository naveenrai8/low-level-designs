package com.lld.resilience;

/**
 * A clock that only moves forward, counted in nanoseconds.
 *
 * <p>The breaker measures "how long has it been open" and "how old is this outcome", which are elapsed-time questions. Tests pass a fake ticker and move it by hand, so no test ever sleeps.
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
