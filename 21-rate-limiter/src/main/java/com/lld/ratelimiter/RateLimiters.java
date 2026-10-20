package com.lld.ratelimiter;

/** Factory for the built-in algorithms. Callers depend on {@link RateLimiter}, not the classes. */
public final class RateLimiters {

    /** Algorithm choice, e.g. from configuration. */
    public enum Algorithm {
        /** Bursty-then-steady; O(1) memory. The usual default for API limits. */
        TOKEN_BUCKET,
        /** Exact; O(limit) memory per key. For small, strict limits. */
        SLIDING_WINDOW_LOG,
        /** Approximate; O(1) memory, no boundary burst. For high-volume limits. */
        SLIDING_WINDOW_COUNTER
    }

    private RateLimiters() {}

    public static KeyedRateLimiter<?> create(Algorithm algorithm, RateLimit limit, Ticker ticker) {
        return switch (algorithm) {
            case TOKEN_BUCKET -> new KeyedRateLimiter<>(new TokenBucket(limit), ticker);
            case SLIDING_WINDOW_LOG -> new KeyedRateLimiter<>(new SlidingWindowLog(limit), ticker);
            case SLIDING_WINDOW_COUNTER -> new KeyedRateLimiter<>(new SlidingWindowCounter(limit), ticker);
        };
    }
}
