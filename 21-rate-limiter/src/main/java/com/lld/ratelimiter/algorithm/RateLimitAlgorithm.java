package com.lld.ratelimiter.algorithm;

import com.lld.ratelimiter.Decision;
import com.lld.ratelimiter.KeyedRateLimiter;
import com.lld.ratelimiter.RateLimit;

/**
 * The math of one rate-limiting algorithm, working on one key's state at a time (the Strategy pattern).
 *
 * <p>Implementations are deliberately <em>not</em> thread-safe. {@link KeyedRateLimiter} calls every method for a key while holding that key's lock, so an algorithm only has to be correct for one thread. That is what keeps each one short enough to write in an interview.
 *
 * @param <S> the per-key state this algorithm keeps and changes
 */
public interface RateLimitAlgorithm<S> {

    /** Creates the state for a key seen for the first time at {@code nowNanos}, with the full limit available. */
    S newState(long nowNanos);

    /** Checks whether {@code permits} fit right now and, if they do, takes them. It changes {@code state}, and is always called under the key's lock. */
    Decision tryAcquire(S state, long nowNanos, int permits);

    /**
     * Returns true when {@code state} now behaves exactly like a brand-new state, so throwing it away loses nothing.
     *
     * <p>This is what makes forgetting idle keys safe: we only ever forget a key whose limit has fully recovered.
     */
    boolean isIdle(S state, long nowNanos);

    /** Returns the limit being enforced, used to reject requests that could never succeed. */
    RateLimit limit();
}
