package com.lld.ratelimiter;

/**
 * A clock that only moves forward, counted in nanoseconds.
 *
 * <p>A rate limiter cares about <em>how much time has passed</em>, so it needs a clock that never jumps backwards ({@link System#nanoTime()}). Wall-clock time ({@code Clock}/{@code Instant}) can be moved backwards by time sync, so it is the wrong tool here.
 *
 * <p>Passing the clock in is also what makes every test here repeatable: tests move a fake ticker forward instead of sleeping.
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
