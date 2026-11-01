package com.lld.ratelimiter;

import java.time.Duration;
import java.util.Objects;

/**
 * The answer to "may this request go ahead?", returned by {@link RateLimiter#tryAcquire}.
 *
 * <p>A <em>permit</em> is one unit of "you may make a request". Most requests ask for 1 permit.
 *
 * @param allowed true if the permits were given out, false if the request must wait
 * @param remaining how many permits are still free right after this call (an estimate for the approximate algorithms)
 * @param retryAfter when denied, the shortest wait before the same request could succeed; zero when allowed
 */
public record Decision(boolean allowed, long remaining, Duration retryAfter) {

    /**
     * Rejects impossible answers: a negative "remaining", or an allowed answer that still tells the caller to wait.
     *
     * @throws IllegalArgumentException if the values contradict each other
     */
    public Decision {
        Objects.requireNonNull(retryAfter, "retryAfter");
        if (remaining < 0) {
            throw new IllegalArgumentException("remaining must be >= 0: " + remaining);
        }
        if (allowed && !retryAfter.isZero()) {
            throw new IllegalArgumentException("an allowed decision has no retryAfter");
        }
    }

    /** Builds a "yes" answer that says how many permits are left. */
    public static Decision allow(long remaining) {
        return new Decision(true, remaining, Duration.ZERO);
    }

    /**
     * Builds a "no" answer that says how many permits are left and how long to wait.
     *
     * <p>The wait is never less than 1 nanosecond, because "retry after 0" would make a client retry in a tight loop.
     */
    public static Decision deny(long remaining, long retryAfterNanos) {
        return new Decision(false, remaining, Duration.ofNanos(Math.max(1, retryAfterNanos)));
    }
}
