package com.lld.ratelimiter;

import java.time.Duration;
import java.util.Objects;

/**
 * The rule being enforced: "at most {@code permits} requests per {@code window}", for example 5 per second. It says nothing about which algorithm enforces it.
 *
 * <p>A <em>window</em> is the length of time the limit is counted over (1 second, 1 minute).
 *
 * <p>For the token bucket, {@code permits} is also the bucket size (the biggest burst allowed at once), and the bucket refills at {@code permits / window}.
 */
public record RateLimit(long permits, Duration window) {

    /**
     * Rejects limits that make no sense: zero or negative permits, a zero or negative window, or more than one permit per nanosecond.
     *
     * @throws IllegalArgumentException if the limit is invalid
     */
    public RateLimit {
        Objects.requireNonNull(window, "window");
        if (permits < 1) {
            throw new IllegalArgumentException("permits must be >= 1: " + permits);
        }
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be positive: " + window);
        }
        if (window.toNanos() < permits) {
            // Faster than one token per nanosecond, TokenBucket's whole-number refill math would break.
            throw new IllegalArgumentException("more than one permit per nanosecond is not supported");
        }
    }

    /** Shortcut for "{@code permits} per second". */
    public static RateLimit perSecond(long permits) {
        return new RateLimit(permits, Duration.ofSeconds(1));
    }

    /** Shortcut for "{@code permits} per minute". */
    public static RateLimit perMinute(long permits) {
        return new RateLimit(permits, Duration.ofMinutes(1));
    }

    /** Returns the window length in nanoseconds, the unit all the algorithms work in. */
    public long windowNanos() {
        return window.toNanos();
    }
}
