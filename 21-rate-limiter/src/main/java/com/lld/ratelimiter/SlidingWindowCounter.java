package com.lld.ratelimiter;

/**
 * Sliding-window counter: two fixed-window counts, with the previous window weighted by how much
 * of it still overlaps the sliding window.
 *
 * <pre>
 *   estimate = previous * (window - elapsedInCurrent) / window + current
 * </pre>
 *
 * <p>O(1) memory per key (three longs) and it removes the fixed-window "2x burst at the boundary"
 * flaw. The price is that it is an <em>approximation</em>: it assumes the previous window's
 * requests were evenly spread. If they were all at the very end of that window, it can let through
 * more than {@code permits} within some true window of length {@code window}. (Cloudflare reported
 * a 0.003% error rate for this approach on real traffic; see the topic README.)
 *
 * <p>Windows are aligned to each key's first request after a quiet period, not to the epoch, so
 * keys don't all reset on the same tick (no thundering herd at :00). Re-aligning whenever both
 * counters are zero is also what makes idle-key eviction lossless: a kept-but-quiet key then
 * behaves exactly like a brand-new one.
 */
public final class SlidingWindowCounter implements RateLimitAlgorithm<SlidingWindowCounter.State> {

    /** Mutable per-key state. Guarded by the owning key's lock in {@link KeyedRateLimiter}. */
    public static final class State {
        private long windowStartNanos;
        private long current;
        private long previous;

        private State(long windowStartNanos) {
            this.windowStartNanos = windowStartNanos;
        }
    }

    private final RateLimit limit;
    private final long windowNanos;

    public SlidingWindowCounter(RateLimit limit) {
        this.limit = limit;
        this.windowNanos = limit.windowNanos();
    }

    @Override
    public State newState(long nowNanos) {
        return new State(nowNanos);
    }

    @Override
    public Decision tryAcquire(State s, long nowNanos, int permits) {
        roll(s, nowNanos);
        long elapsed = nowNanos - s.windowStartNanos;
        double estimate = estimate(s, elapsed);
        long max = limit.permits();
        if (estimate + permits <= max) {
            s.current += permits;
            return Decision.allow((long) Math.floor(max - estimate - permits));
        }
        long remaining = Math.max(0, (long) Math.floor(max - estimate));
        return Decision.deny(remaining, retryAfterNanos(s, elapsed, permits));
    }

    @Override
    public boolean isIdle(State s, long nowNanos) {
        long elapsed = nowNanos - s.windowStartNanos;
        // Idle == "both counters would be zero after rolling to now" (see roll()).
        return elapsed >= 2 * windowNanos
                || (elapsed >= windowNanos && s.current == 0)
                || (s.current == 0 && s.previous == 0);
    }

    @Override
    public RateLimit limit() {
        return limit;
    }

    private void roll(State s, long nowNanos) {
        long windowsPassed = (nowNanos - s.windowStartNanos) / windowNanos;
        if (windowsPassed > 0) {
            s.previous = windowsPassed == 1 ? s.current : 0;
            s.current = 0;
            s.windowStartNanos += windowsPassed * windowNanos;
        }
        if (s.current == 0 && s.previous == 0) {
            // Nothing to remember: restart the window grid here, exactly like newState(now).
            s.windowStartNanos = nowNanos;
        }
    }

    private double estimate(State s, long elapsedInCurrent) {
        double previousWeight = (double) (windowNanos - elapsedInCurrent) / windowNanos;
        return s.previous * previousWeight + s.current;
    }

    /**
     * Solves the estimate formula for the earliest time the request fits. Two cases:
     * the current window alone is too full (wait for it to become "previous" and decay), or only
     * the decaying previous window is in the way (wait for it to decay enough).
     */
    private long retryAfterNanos(State s, long elapsed, int permits) {
        long max = limit.permits();
        long untilNextWindow = windowNanos - elapsed;
        if (s.current + permits > max) {
            // In the next window, today's `current` becomes `previous`, weighted (W - e')/W.
            double fraction = 1.0 - (double) (max - permits) / s.current;
            return untilNextWindow + (long) Math.ceil(windowNanos * Math.max(0, fraction));
        }
        double fraction = 1.0 - (double) (max - s.current - permits) / s.previous;
        long neededElapsed = (long) Math.ceil(windowNanos * fraction);
        return neededElapsed - elapsed;
    }
}
