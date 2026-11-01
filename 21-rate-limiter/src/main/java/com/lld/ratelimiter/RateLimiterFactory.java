package com.lld.ratelimiter;

import com.lld.ratelimiter.algorithm.SlidingWindowCounter;
import com.lld.ratelimiter.algorithm.SlidingWindowLog;
import com.lld.ratelimiter.algorithm.TokenBucket;

/** Builds a rate limiter for one of the built-in algorithms, so callers depend on {@link RateLimiter} and never on the algorithm classes. */
public final class RateLimiterFactory {

    /** Which algorithm to use, usually read from configuration. */
    public enum Algorithm {
        /** Allows a burst, then a steady rate. Tiny memory per key. The usual default for API limits. */
        TOKEN_BUCKET,
        /** Exact, but remembers every request, so memory grows with the limit. For small, strict limits. */
        SLIDING_WINDOW_LOG,
        /** Close to exact, with tiny memory per key and no burst at window edges. For high-volume limits. */
        SLIDING_WINDOW_COUNTER
    }

    /** Not meant to be created; use {@link #create}. */
    private RateLimiterFactory() {}

    /** Creates a per-key limiter that enforces {@code limit} with the chosen {@code algorithm}, using {@code ticker} as its clock. */
    public static KeyedRateLimiter<?> create(Algorithm algorithm, RateLimit limit, Ticker ticker) {
        return switch (algorithm) {
            case TOKEN_BUCKET -> new KeyedRateLimiter<>(new TokenBucket(limit), ticker);
            case SLIDING_WINDOW_LOG -> new KeyedRateLimiter<>(new SlidingWindowLog(limit), ticker);
            case SLIDING_WINDOW_COUNTER -> new KeyedRateLimiter<>(new SlidingWindowCounter(limit), ticker);
        };
    }
}
