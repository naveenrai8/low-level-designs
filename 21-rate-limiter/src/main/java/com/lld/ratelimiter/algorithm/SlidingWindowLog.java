package com.lld.ratelimiter.algorithm;

import com.lld.ratelimiter.Decision;
import com.lld.ratelimiter.KeyedRateLimiter;
import com.lld.ratelimiter.RateLimit;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Sliding-window log: remembers the time of every permit given out in the last {@code window}, and allows a request only if it still fits.
 *
 * <p>Example at 5 per second: if 5 permits were given out in the last second, the next request waits until the oldest of them is more than 1 second old.
 *
 * <p>It is exact: there are never more than {@code permits} grants in any window {@code (now - window, now]}. The price is memory: one timestamp per permit, so at 10,000 per minute a busy key can hold 10,000 boxed longs (about 200 KB). Use it when the limit is small and must be exact (login attempts, OTP sends). For high limits prefer {@link SlidingWindowCounter} or {@link TokenBucket}.
 */
public final class SlidingWindowLog implements RateLimitAlgorithm<SlidingWindowLog.State> {

    /** One key's log of grant times. Protected by that key's lock in {@link KeyedRateLimiter}. */
    public static final class State {
        /** The time of each permit given out, oldest first. */
        private final ArrayDeque<Long> grants = new ArrayDeque<>();
    }

    private final RateLimit limit;
    private final long windowNanos;

    /** Sets up the log for {@code limit}. */
    public SlidingWindowLog(RateLimit limit) {
        this.limit = limit;
        this.windowNanos = limit.windowNanos();
    }

    /** Creates an empty log for a new key. */
    @Override
    public State newState(long nowNanos) {
        return new State();
    }

    /**
     * Drops grants that have left the window, then allows the request if it still fits under the limit.
     *
     * <p>When denied, the wait is how long until enough of the oldest grants leave the window to make room.
     */
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
        // To make room, (used + permits - limit) of the oldest grants must leave the window; the last of those to leave decides the wait.
        long mustExpire = used + permits - limit.permits();
        Iterator<Long> it = s.grants.iterator();
        long blocking = 0;
        for (long i = 0; i < mustExpire; i++) {
            blocking = it.next();
        }
        return Decision.deny(limit.permits() - used, blocking + windowNanos - nowNanos);
    }

    /** Returns true when every grant in the log has left the window, so the log is as good as empty. */
    @Override
    public boolean isIdle(State s, long nowNanos) {
        Long newest = s.grants.peekLast();
        return newest == null || newest <= nowNanos - windowNanos;
    }

    /** Returns the limit this log enforces. */
    @Override
    public RateLimit limit() {
        return limit;
    }

    /** Removes grants that are no longer in the window. A grant made at time t counts while t is in {@code (now - window, now]}. */
    private void expire(State s, long nowNanos) {
        long cutoff = nowNanos - windowNanos;
        while (!s.grants.isEmpty() && s.grants.peekFirst() <= cutoff) {
            s.grants.pollFirst();
        }
    }
}
