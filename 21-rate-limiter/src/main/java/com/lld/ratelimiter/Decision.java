package com.lld.ratelimiter;

import java.time.Duration;
import java.util.Objects;

/**
 * Outcome of a {@link RateLimiter#tryAcquire} call.
 *
 * @param allowed whether the permits were granted
 * @param remaining permits still available immediately after this call (best-effort estimate for
 *     approximate algorithms)
 * @param retryAfter when denied, the minimum wait before the same request could succeed;
 *     {@link Duration#ZERO} when allowed
 */
public record Decision(boolean allowed, long remaining, Duration retryAfter) {

    public Decision {
        Objects.requireNonNull(retryAfter, "retryAfter");
        if (remaining < 0) {
            throw new IllegalArgumentException("remaining must be >= 0: " + remaining);
        }
        if (allowed && !retryAfter.isZero()) {
            throw new IllegalArgumentException("an allowed decision has no retryAfter");
        }
    }

    static Decision allow(long remaining) {
        return new Decision(true, remaining, Duration.ZERO);
    }

    static Decision deny(long remaining, long retryAfterNanos) {
        // Clamp to >= 1ns: a denial that says "retry after 0" invites a hot retry loop.
        return new Decision(false, remaining, Duration.ofNanos(Math.max(1, retryAfterNanos)));
    }
}
