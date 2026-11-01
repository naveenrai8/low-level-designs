package com.lld.connpool;

import java.time.Duration;

/**
 * The knobs of the pool.
 *
 * @param min             the pool never evicts below this many connections (it does not open them by itself, call {@code warmUp})
 * @param max             the hard cap on live connections (idle + borrowed + being opened + being closed)
 * @param maxIdle         an idle connection older than this may be evicted
 * @param leakThreshold   a connection held longer than this is reported as a possible leak
 * @param captureStacks   when true, every borrow records the borrower's stack (costs a little per borrow, pays off when hunting a leak)
 */
public record PoolConfig(int min, int max, Duration maxIdle, Duration leakThreshold, boolean captureStacks) {

    /** Checks the numbers make sense: {@code 0 <= min <= max}, {@code max >= 1}, and the durations are not negative. */
    public PoolConfig {
        if (max < 1 || min < 0 || min > max) {
            throw new IllegalArgumentException("need 0 <= min <= max and max >= 1, got min=" + min + " max=" + max);
        }
        if (maxIdle.isNegative() || leakThreshold.isNegative()) {
            throw new IllegalArgumentException("durations must not be negative");
        }
    }

    /** Returns a config with the given sizes, 10 minute idle limit, 30 second leak threshold and stack capture on. */
    public static PoolConfig of(int min, int max) {
        return new PoolConfig(min, max, Duration.ofMinutes(10), Duration.ofSeconds(30), true);
    }
}
