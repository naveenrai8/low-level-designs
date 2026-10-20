package com.lld.ratelimiter;

/**
 * Token bucket: capacity {@code permits}, refilled continuously at {@code permits / window}.
 *
 * <p>Allows bursts up to the capacity and then a steady rate. O(1) time and O(1) memory per key
 * (two longs). Tokens are whole numbers and refill is computed lazily on access — no background
 * thread ever touches the buckets.
 *
 * <p>Precision note: {@code nanosPerToken = window / permits} uses integer division, so the
 * effective rate can be fractionally <em>higher</em> than configured (by at most 1ns per token,
 * i.e. negligible for any limit below ~10^6/s).
 */
public final class TokenBucket implements RateLimitAlgorithm<TokenBucket.State> {

    /** Mutable per-key state. Guarded by the owning key's lock in {@link KeyedRateLimiter}. */
    public static final class State {
        private long tokens;
        private long lastRefillNanos;

        private State(long tokens, long lastRefillNanos) {
            this.tokens = tokens;
            this.lastRefillNanos = lastRefillNanos;
        }
    }

    private final RateLimit limit;
    private final long capacity;
    private final long nanosPerToken;

    public TokenBucket(RateLimit limit) {
        this.limit = limit;
        this.capacity = limit.permits();
        this.nanosPerToken = limit.windowNanos() / limit.permits();
    }

    @Override
    public State newState(long nowNanos) {
        return new State(capacity, nowNanos);
    }

    @Override
    public Decision tryAcquire(State s, long nowNanos, int permits) {
        refill(s, nowNanos);
        if (s.tokens >= permits) {
            s.tokens -= permits;
            return Decision.allow(s.tokens);
        }
        // The k-th missing token arrives k * nanosPerToken after the last refill point.
        long deficit = permits - s.tokens;
        long waitNanos = s.lastRefillNanos + deficit * nanosPerToken - nowNanos;
        return Decision.deny(s.tokens, waitNanos);
    }

    @Override
    public boolean isIdle(State s, long nowNanos) {
        return tokensEarnedSince(s, nowNanos) >= capacity - s.tokens;
    }

    @Override
    public RateLimit limit() {
        return limit;
    }

    private void refill(State s, long nowNanos) {
        long earned = tokensEarnedSince(s, nowNanos);
        if (earned == 0) {
            return;
        }
        if (earned >= capacity - s.tokens) {
            // Full. Reset the refill point to now so idle time beyond "full" is not banked.
            s.tokens = capacity;
            s.lastRefillNanos = nowNanos;
        } else {
            s.tokens += earned;
            // Advance only by the time actually converted into tokens, keeping the partial
            // progress toward the next token. Advancing to nowNanos here would leak rate.
            s.lastRefillNanos += earned * nanosPerToken;
        }
    }

    private long tokensEarnedSince(State s, long nowNanos) {
        long elapsed = nowNanos - s.lastRefillNanos;
        return elapsed <= 0 ? 0 : elapsed / nanosPerToken;
    }
}
