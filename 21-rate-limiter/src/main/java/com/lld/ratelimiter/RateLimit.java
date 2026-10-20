package com.lld.ratelimiter;

import java.time.Duration;
import java.util.Objects;

/**
 * "At most {@code permits} per {@code window}" — the policy, separate from the algorithm that
 * enforces it.
 *
 * <p>For the token bucket, {@code permits} is also the bucket capacity (the maximum burst) and the
 * refill rate is {@code permits / window}.
 */
public record RateLimit(long permits, Duration window) {

    public RateLimit {
        Objects.requireNonNull(window, "window");
        if (permits < 1) {
            throw new IllegalArgumentException("permits must be >= 1: " + permits);
        }
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be positive: " + window);
        }
        if (window.toNanos() < permits) {
            // Below 1 token per nanosecond the integer refill math in TokenBucket degenerates.
            throw new IllegalArgumentException("more than one permit per nanosecond is not supported");
        }
    }

    public static RateLimit perSecond(long permits) {
        return new RateLimit(permits, Duration.ofSeconds(1));
    }

    public static RateLimit perMinute(long permits) {
        return new RateLimit(permits, Duration.ofMinutes(1));
    }

    long windowNanos() {
        return window.toNanos();
    }
}
