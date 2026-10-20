package com.lld.ratelimiter;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, per-key {@link RateLimiter} that delegates the math to a single-threaded
 * {@link RateLimitAlgorithm}.
 *
 * <h2>Concurrency model</h2>
 * <ul>
 *   <li><b>Shared state:</b> one algorithm state object per key, held in a {@link ConcurrentHashMap}.
 *   <li><b>Invariant:</b> for a given key, read-time → check → consume happens atomically, so N
 *       racing callers can never be granted more than the limit.
 *   <li><b>Primitive:</b> {@link ConcurrentHashMap#compute}, which runs the lambda while holding the
 *       lock for that key's hash bin. The same key is serialized; different keys proceed in
 *       parallel unless they share a bin (there is no global lock). Eviction uses
 *       {@link ConcurrentHashMap#computeIfPresent} on the same bin lock, so it can never race with
 *       an in-flight acquire on that key.
 *   <li><b>Time is read inside the lock.</b> Reading it before {@code compute} lets a thread with an
 *       older timestamp run after one with a newer timestamp, making per-key time go backwards.
 * </ul>
 *
 * <p>The lambdas passed to {@code compute} must stay short and must never touch another key in the
 * same map — that can deadlock or throw {@code IllegalStateException("Recursive update")}.
 *
 * <p><b>In-memory limitation:</b> state lives in this JVM only. Behind a load balancer with K
 * instances, each enforces its own copy, so the effective limit becomes up to K × limit. See the
 * README's distributed follow-up.
 *
 * @param <S> the algorithm's per-key state type
 */
public final class KeyedRateLimiter<S> implements RateLimiter {

    private final RateLimitAlgorithm<S> algorithm;
    private final Ticker ticker;
    private final ConcurrentHashMap<String, S> states = new ConcurrentHashMap<>();

    public KeyedRateLimiter(RateLimitAlgorithm<S> algorithm, Ticker ticker) {
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    @Override
    public Decision tryAcquire(String key, int permits) {
        validate(key, permits);
        // compute() returns the new map value, not our Decision, so capture it in a local holder.
        Decision[] decision = new Decision[1];
        states.compute(key, (k, existing) -> {
            long now = ticker.read();
            S state = existing != null ? existing : algorithm.newState(now);
            decision[0] = algorithm.tryAcquire(state, now, permits);
            return state;
        });
        return decision[0];
    }

    /**
     * Removes every key whose limit has fully recovered, bounding memory to "keys active within
     * roughly one window". Lossless: an evicted key behaves exactly as if it had been kept.
     *
     * <p>Callers decide when to run it (a scheduled task, or every N calls). It is O(number of
     * keys), and it is weakly consistent: keys added while it runs may or may not be visited.
     *
     * @return how many keys were evicted
     */
    public int evictIdle() {
        long now = ticker.read();
        int[] evicted = {0};
        for (String key : states.keySet()) {
            states.computeIfPresent(key, (k, state) -> {
                if (algorithm.isIdle(state, now)) {
                    evicted[0]++;
                    return null; // returning null removes the mapping
                }
                return state;
            });
        }
        return evicted[0];
    }

    /** Number of keys currently holding state. */
    public int trackedKeys() {
        return states.size();
    }

    public RateLimit limit() {
        return algorithm.limit();
    }

    private void validate(String key, int permits) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must be non-blank");
        }
        if (permits < 1) {
            throw new IllegalArgumentException("permits must be >= 1: " + permits);
        }
        if (permits > algorithm.limit().permits()) {
            throw new IllegalArgumentException(
                    "permits " + permits + " exceeds the limit " + algorithm.limit().permits()
                            + "; the request could never succeed");
        }
    }
}
