package com.lld.ratelimiter.algorithm;

import com.lld.ratelimiter.Decision;
import com.lld.ratelimiter.KeyedRateLimiter;
import com.lld.ratelimiter.RateLimit;

/**
 * Token bucket: each key has a bucket that holds up to {@code permits} tokens and refills at a steady rate of {@code permits / window}.
 *
 * <p>A <em>token</em> is one permission to make a request. A request spends tokens; if the bucket doesn't have enough, the request is denied.
 *
 * <p>Example at 10 per second: the bucket holds 10 tokens and gains one every 100 ms. A full bucket lets 10 requests through at once, then about one every 100 ms.
 *
 * <p>Cost: a constant amount of work and two numbers of memory per key. Tokens are whole numbers. Refill is worked out only when the key is used, so no background thread ever touches the buckets.
 *
 * <p>Precision note: {@code nanosPerToken = window / permits} rounds down, so the real rate can be a tiny bit <em>faster</em> than configured (at most 1 ns per token, which only matters near a million per second).
 */
public final class TokenBucket implements RateLimitAlgorithm<TokenBucket.State> {

    /** One key's bucket: how many tokens it holds, and when it was last refilled. Protected by that key's lock in {@link KeyedRateLimiter}. */
    public static final class State {
        private long tokens;
        private long lastRefillNanos;

        /** Creates a bucket holding {@code tokens}, last refilled at {@code lastRefillNanos}. */
        private State(long tokens, long lastRefillNanos) {
            this.tokens = tokens;
            this.lastRefillNanos = lastRefillNanos;
        }
    }

    private final RateLimit limit;
    private final long capacity;
    private final long nanosPerToken;

    /** Sets up the bucket size and how often one token is added, from {@code limit} (for example 10 per second means one token every 100 ms). */
    public TokenBucket(RateLimit limit) {
        this.limit = limit;
        this.capacity = limit.permits();
        this.nanosPerToken = limit.windowNanos() / limit.permits();
    }

    /** Creates a full bucket for a new key, so a new client can burst straight away. */
    @Override
    public State newState(long nowNanos) {
        return new State(capacity, nowNanos);
    }

    /**
     * Tops up the bucket, then spends {@code permits} tokens if there are enough.
     *
     * <p>If there aren't enough, nothing is spent, and the answer says exactly when the missing tokens will have arrived.
     */
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

    /** Returns true once enough time has passed for the bucket to be full again, because a full bucket is the same as a new one. */
    @Override
    public boolean isIdle(State s, long nowNanos) {
        return tokensEarnedSince(s, nowNanos) >= capacity - s.tokens;
    }

    /** Returns the limit this bucket enforces. */
    @Override
    public RateLimit limit() {
        return limit;
    }

    /**
     * Adds the tokens earned since the last refill, never going above the bucket's capacity.
     *
     * <p>Example at 10 per second (one token every 100 ms): 250 ms after the last refill, 2 tokens are added and the leftover 50 ms is kept toward the next token.
     *
     * <p>When the bucket fills up, the refill point jumps to now, so time spent sitting full is not saved up as extra tokens.
     */
    private void refill(State s, long nowNanos) {
        long earned = tokensEarnedSince(s, nowNanos);
        if (earned == 0) {
            return;
        }
        if (earned >= capacity - s.tokens) {
            // Full. Move the refill point to now, so idle time beyond "full" is not saved up.
            s.tokens = capacity;
            s.lastRefillNanos = nowNanos;
        } else {
            s.tokens += earned;
            // Move the refill point forward only by the time turned into tokens, so the leftover part of a token is not lost (moving it to now would slowly lower the rate).
            s.lastRefillNanos += earned * nanosPerToken;
        }
    }

    /** Returns how many whole tokens have been earned since the last refill point (part-earned tokens don't count yet). */
    private long tokensEarnedSince(State s, long nowNanos) {
        long elapsed = nowNanos - s.lastRefillNanos;
        return elapsed <= 0 ? 0 : elapsed / nanosPerToken;
    }
}
