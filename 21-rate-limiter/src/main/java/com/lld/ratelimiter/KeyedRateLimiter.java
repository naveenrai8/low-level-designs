package com.lld.ratelimiter;

import com.lld.ratelimiter.algorithm.RateLimitAlgorithm;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A thread-safe rate limiter that keeps a separate limit for every key (for example, one per user), and hands the math to a {@link RateLimitAlgorithm}.
 *
 * <p>A <em>key</em> is whatever you limit by: a user id, an API key, an IP address.
 *
 * <h2>How it stays correct when many threads call it at once</h2>
 * <ul>
 *   <li><b>Shared state:</b> one small state object per key, stored in a {@link ConcurrentHashMap}.</li>
 *   <li><b>Rule that must always hold:</b> for one key, "read the time, check, take the permits" happens as one step, so racing callers can never get more than the limit.</li>
 *   <li><b>Tool used:</b> {@link ConcurrentHashMap#compute}, which runs our code while holding a lock for that key's slot in the map. Requests for the same key wait for each other; different keys usually run in parallel (there is no global lock). Eviction uses {@link ConcurrentHashMap#computeIfPresent} on the same lock, so it can never clash with a request for that key.</li>
 *   <li><b>Time is read inside the lock.</b> If it were read before {@code compute}, a thread holding an older time could run after a thread holding a newer time, and that key's time would go backwards.</li>
 * </ul>
 *
 * <p>The code passed to {@code compute} must stay short and must never touch another key in the same map, because that can deadlock or throw {@code IllegalStateException("Recursive update")}.
 *
 * <p><b>Limitation:</b> the state lives only in this JVM. With K copies of the service behind a load balancer, each copy has its own count, so a client can get up to K × the limit. See the README's distributed follow-up.
 *
 * @param <S> the type of the per-key state the algorithm keeps
 */
public final class KeyedRateLimiter<S> implements RateLimiter {

    private final RateLimitAlgorithm<S> algorithm;
    private final Ticker ticker;
    private final ConcurrentHashMap<String, S> states = new ConcurrentHashMap<>();

    /**
     * Creates a limiter that uses {@code algorithm} for the math and {@code ticker} as its clock.
     *
     * @throws NullPointerException if either argument is null
     */
    public KeyedRateLimiter(RateLimitAlgorithm<S> algorithm, Ticker ticker) {
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    /**
     * Checks whether {@code key} may spend {@code permits} right now, and spends them if so.
     *
     * <p>The check and the spend happen together under that key's lock, so two requests at the same instant can never both take the last permit. The time is read inside the lock too, so each key always sees time moving forward. A key seen for the first time starts with its full limit.
     *
     * @return allowed (with how many permits are left) or denied (with how long to wait)
     * @throws IllegalArgumentException if the key is blank, permits is below 1, or permits is more than the limit (that request could never succeed)
     */
    @Override
    public Decision tryAcquire(String key, int permits) {
        validate(key, permits);
        // compute() returns the new map value, not our Decision, so we pass the Decision out through a one-element array.
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
     * Forgets every key whose limit has fully recovered, so memory only holds keys that were active recently (roughly within one window).
     *
     * <p>Forgetting such a key changes nothing: if it comes back, it gets a fresh state that behaves exactly like the one we dropped.
     *
     * <p>The caller decides when to run it (for example, on a timer). It visits every key, and keys added while it runs may or may not be visited.
     *
     * @return how many keys were forgotten
     */
    public int evictIdle() {
        long now = ticker.read();
        int[] evicted = {0};
        for (String key : states.keySet()) {
            states.computeIfPresent(key, (k, state) -> {
                if (algorithm.isIdle(state, now)) {
                    evicted[0]++;
                    return null; // returning null removes the key from the map
                }
                return state;
            });
        }
        return evicted[0];
    }

    /** Returns how many keys currently have state in memory. */
    public int trackedKeys() {
        return states.size();
    }

    /** Returns the limit this limiter enforces, for example "5 per second". */
    public RateLimit limit() {
        return algorithm.limit();
    }

    /**
     * Rejects requests that are malformed or could never succeed, before any state is touched.
     *
     * <p>Asking for more permits than the limit throws instead of returning "denied", because "retry later" would make the client retry forever.
     *
     * @throws IllegalArgumentException if the key is null or blank, permits is below 1, or permits is above the limit
     */
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
