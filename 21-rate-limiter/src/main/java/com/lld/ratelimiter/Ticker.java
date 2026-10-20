package com.lld.ratelimiter;

/**
 * A monotonic nanosecond time source.
 *
 * <p>Rate limiters measure <em>elapsed</em> time, so they need a monotonic clock
 * ({@link System#nanoTime()}), not wall-clock time ({@code Clock}/{@code Instant}), which NTP can
 * move backwards. Injecting it is also what makes every test in this module deterministic: tests
 * advance a fake ticker instead of sleeping.
 */
@FunctionalInterface
public interface Ticker {

    /** Nanoseconds from an arbitrary fixed origin. Only differences between readings are meaningful. */
    long read();

    static Ticker system() {
        return System::nanoTime;
    }
}
