package com.lld.ratelimiter;

/**
 * Strategy: the per-key bookkeeping of one rate-limiting algorithm.
 *
 * <p>Implementations are deliberately <em>not</em> thread-safe. {@link KeyedRateLimiter} calls
 * every method for a given key while holding that key's lock, so an algorithm only has to be
 * correct for a single thread — which is what keeps each one short enough to write in an
 * interview.
 *
 * @param <S> mutable per-key state
 */
public interface RateLimitAlgorithm<S> {

    /** State for a key seen for the first time at {@code nowNanos}: the full limit is available. */
    S newState(long nowNanos);

    /** Check-and-consume; mutates {@code state}. Called under the key's lock. */
    Decision tryAcquire(S state, long nowNanos, int permits);

    /**
     * True when {@code state} is indistinguishable from {@link #newState} at {@code nowNanos}, so
     * dropping it loses no information. This is what makes idle-key eviction <em>lossless</em>:
     * we only ever evict keys whose limit has fully recovered.
     */
    boolean isIdle(S state, long nowNanos);

    /** The policy being enforced; used for argument validation. */
    RateLimit limit();
}
