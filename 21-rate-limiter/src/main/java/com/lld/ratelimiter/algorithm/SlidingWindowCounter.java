package com.lld.ratelimiter.algorithm;

import com.lld.ratelimiter.Decision;
import com.lld.ratelimiter.KeyedRateLimiter;
import com.lld.ratelimiter.RateLimit;

/**
 * Sliding-window counter: keeps a count for the current window and the previous one, and counts the previous window only for the part that still overlaps "the last {@code window}".
 *
 * <pre>
 *   estimate = previous * (window - elapsedInCurrent) / window + current
 * </pre>
 *
 * <p>Example at 10 per second: 10 requests last second and 30% of the way into this second gives an estimate of 10 × 0.7 + current. A request is allowed if the estimate plus the request still fits under 10.
 *
 * <p>It needs only three numbers per key, and it fixes the simple fixed window's flaw of letting 2× the limit through at a window edge. The price is that it is an <em>estimate</em>: it assumes last window's requests were spread evenly. If they were all bunched at the very end, it can let more than {@code permits} through in some real window. (Cloudflare measured a 0.003% error rate on real traffic; see the topic README.)
 *
 * <p>Windows start at each key's first request after a quiet period, not at fixed clock ticks, so all keys don't reset at the same moment. Restarting whenever both counts are zero is also what makes forgetting idle keys safe: a quiet key then behaves exactly like a brand-new one.
 */
public final class SlidingWindowCounter implements RateLimitAlgorithm<SlidingWindowCounter.State> {

    /** One key's counts: when the current window started, and how many permits were given out in this window and the previous one. Protected by that key's lock in {@link KeyedRateLimiter}. */
    public static final class State {
        private long windowStartNanos;
        private long current;
        private long previous;

        /** Creates empty counts with the current window starting at {@code windowStartNanos}. */
        private State(long windowStartNanos) {
            this.windowStartNanos = windowStartNanos;
        }
    }

    private final RateLimit limit;
    private final long windowNanos;

    /** Sets up the counter for {@code limit}. */
    public SlidingWindowCounter(RateLimit limit) {
        this.limit = limit;
        this.windowNanos = limit.windowNanos();
    }

    /** Creates empty counts for a new key, with its first window starting now. */
    @Override
    public State newState(long nowNanos) {
        return new State(nowNanos);
    }

    /**
     * Moves the windows forward to now, then allows the request if the estimate plus the request fits under the limit.
     *
     * <p>When denied, the wait comes from solving the estimate formula for the earliest moment the request fits.
     */
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

    /** Returns true when both counts would be zero after moving the windows forward to now, so the state is as good as new. */
    @Override
    public boolean isIdle(State s, long nowNanos) {
        long elapsed = nowNanos - s.windowStartNanos;
        // Idle means "both counts would be zero after rolling to now" (see roll()).
        return elapsed >= 2 * windowNanos
                || (elapsed >= windowNanos && s.current == 0)
                || (s.current == 0 && s.previous == 0);
    }

    /** Returns the limit this counter enforces. */
    @Override
    public RateLimit limit() {
        return limit;
    }

    /**
     * Moves the windows forward to now.
     *
     * <p>After exactly one window passes, "current" becomes "previous". After two or more, both are zero. If both counts end up zero, the window restarts at now, exactly as for a new key.
     */
    private void roll(State s, long nowNanos) {
        long windowsPassed = (nowNanos - s.windowStartNanos) / windowNanos;
        if (windowsPassed > 0) {
            s.previous = windowsPassed == 1 ? s.current : 0;
            s.current = 0;
            s.windowStartNanos += windowsPassed * windowNanos;
        }
        if (s.current == 0 && s.previous == 0) {
            // Nothing to remember: restart the window here, exactly like newState(now).
            s.windowStartNanos = nowNanos;
        }
    }

    /** Returns the estimated number of permits used in the last {@code window}: all of the current window plus the still-overlapping share of the previous one. */
    private double estimate(State s, long elapsedInCurrent) {
        double previousWeight = (double) (windowNanos - elapsedInCurrent) / windowNanos;
        return s.previous * previousWeight + s.current;
    }

    /**
     * Works out how long until the request fits, by solving the estimate formula for time.
     *
     * <p>There are two cases. If the current window alone is too full, wait for it to become "previous" and fade enough. Otherwise only the fading previous window is in the way, so wait for it to fade enough.
     */
    private long retryAfterNanos(State s, long elapsed, int permits) {
        long max = limit.permits();
        long untilNextWindow = windowNanos - elapsed;
        if (s.current + permits > max) {
            // In the next window, today's `current` becomes `previous`, weighted by (W - e')/W.
            double fraction = 1.0 - (double) (max - permits) / s.current;
            return untilNextWindow + (long) Math.ceil(windowNanos * Math.max(0, fraction));
        }
        double fraction = 1.0 - (double) (max - s.current - permits) / s.previous;
        long neededElapsed = (long) Math.ceil(windowNanos * fraction);
        return neededElapsed - elapsed;
    }
}
