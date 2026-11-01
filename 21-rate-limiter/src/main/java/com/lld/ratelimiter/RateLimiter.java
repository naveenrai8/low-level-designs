package com.lld.ratelimiter;

/**
 * Decides whether the caller identified by {@code key} may go ahead right now.
 *
 * <p>This is the interface you write on the whiteboard first. Callers depend only on it, and every algorithm (token bucket, sliding log, sliding counter, a Redis-backed one later) is a hidden detail behind it.
 *
 * <p>Implementations must be thread-safe, because many request threads call {@link #tryAcquire} at once, for the same key and for different keys.
 */
public interface RateLimiter {

    /** Asks for 1 permit; the same as {@code tryAcquire(key, 1)}. */
    default Decision tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    /**
     * Checks and takes {@code permits} for {@code key} in one step.
     *
     * <p>It is all or nothing: either every permit is taken (allowed) or none are (denied). It never takes only some of them.
     *
     * @throws IllegalArgumentException if {@code key} is blank, {@code permits < 1}, or {@code permits} is more than the limit (that request could never succeed)
     */
    Decision tryAcquire(String key, int permits);
}
