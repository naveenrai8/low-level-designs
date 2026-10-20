package com.lld.ratelimiter;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Sliding-window log: remembers the timestamp of every granted permit in the last {@code window}.
 *
 * <p>Exact — no request is ever allowed if more than {@code permits} were granted in
 * {@code (now - window, now]} — but memory is O(permits) per key: at 10,000/min that is up to
 * 10,000 boxed longs (~200 KB) for every hot key. Use it when the limit is small and exactness
 * matters (login attempts, OTP sends); prefer {@link SlidingWindowCounter} or {@link TokenBucket}
 * for high limits.
 */
public final class SlidingWindowLog implements RateLimitAlgorithm<SlidingWindowLog.State> {

    /** Mutable per-key state. Guarded by the owning key's lock in {@link KeyedRateLimiter}. */
    public static final class State {
        /** Grant timestamps, oldest first. */
        private final ArrayDeque<Long> grants = new ArrayDeque<>();
    }

    private final RateLimit limit;
    private final long windowNanos;

    public SlidingWindowLog(RateLimit limit) {
        this.limit = limit;
        this.windowNanos = limit.windowNanos();
    }

    @Override
    public State newState(long nowNanos) {
        return new State();
    }

    @Override
    public Decision tryAcquire(State s, long nowNanos, int permits) {
        expire(s, nowNanos);
        int used = s.grants.size();
        if (used + permits <= limit.permits()) {
            for (int i = 0; i < permits; i++) {
                s.grants.addLast(nowNanos);
            }
            return Decision.allow(limit.permits() - used - permits);
        }
        // We need (used + permits - limit) of the oldest grants to expire. The last of those
        // to go is the one that determines the wait.
        long mustExpire = used + permits - limit.permits();
        Iterator<Long> it = s.grants.iterator();
        long blocking = 0;
        for (long i = 0; i < mustExpire; i++) {
            blocking = it.next();
        }
        return Decision.deny(limit.permits() - used, blocking + windowNanos - nowNanos);
    }

    @Override
    public boolean isIdle(State s, long nowNanos) {
        Long newest = s.grants.peekLast();
        return newest == null || newest <= nowNanos - windowNanos;
    }

    @Override
    public RateLimit limit() {
        return limit;
    }

    /** A grant at time t counts while t is in (now - window, now]. */
    private void expire(State s, long nowNanos) {
        long cutoff = nowNanos - windowNanos;
        while (!s.grants.isEmpty() && s.grants.peekFirst() <= cutoff) {
            s.grants.pollFirst();
        }
    }
}
