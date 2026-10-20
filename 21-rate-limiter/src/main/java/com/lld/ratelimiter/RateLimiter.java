package com.lld.ratelimiter;

/**
 * Decides whether a caller identified by {@code key} may proceed right now.
 *
 * <p>This is the interface you write on the whiteboard first: callers depend on it, and every
 * algorithm (token bucket, sliding log, sliding counter, a Redis-backed one later) is an
 * implementation detail behind it.
 *
 * <p>Implementations must be thread-safe: many request threads call {@link #tryAcquire} for the
 * same and different keys concurrently.
 */
public interface RateLimiter {

    /** Equivalent to {@code tryAcquire(key, 1)}. */
    default Decision tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    /**
     * Atomically checks and consumes {@code permits} for {@code key}. Either all permits are
     * consumed (allowed) or none are (denied) — never a partial take.
     *
     * @throws IllegalArgumentException if {@code key} is blank, {@code permits < 1}, or
     *     {@code permits} exceeds the limit (such a request could never succeed)
     */
    Decision tryAcquire(String key, int permits);
}
