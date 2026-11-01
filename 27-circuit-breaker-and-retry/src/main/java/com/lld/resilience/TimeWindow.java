package com.lld.resilience;

import java.util.ArrayDeque;

/**
 * A window of the outcomes from the last T nanoseconds.
 *
 * <p>An outcome recorded at time {@code t} is inside the window while {@code now - t < T}. At exactly {@code now - t == T} it has left. That boundary is tested to the nanosecond.
 */
final class TimeWindow implements SlidingWindow {

    private final long windowNanos;
    private final ArrayDeque<long[]> outcomes = new ArrayDeque<>();
    private int failures;

    /**
     * Creates a window.
     *
     * @param windowNanos how long an outcome stays, at least 1
     */
    public TimeWindow(long windowNanos) {
        if (windowNanos < 1) {
            throw new IllegalArgumentException("windowNanos must be at least 1");
        }
        this.windowNanos = windowNanos;
    }

    /** Drops outcomes that are {@code windowNanos} old or older. Outcomes arrive in time order, so only the head can be old. */
    private void evict(long now) {
        while (!outcomes.isEmpty() && now - outcomes.peekFirst()[0] >= windowNanos) {
            long[] old = outcomes.pollFirst();
            if (old[1] == 0) {
                failures--;
            }
        }
    }

    /** Adds an outcome stamped with the given time, after dropping the old ones. */
    @Override
    public void record(boolean success, long now) {
        evict(now);
        outcomes.addLast(new long[] {now, success ? 1 : 0});
        if (!success) {
            failures++;
        }
    }

    /** Returns how many outcomes are still inside the window at the given time. */
    @Override
    public int total(long now) {
        evict(now);
        return outcomes.size();
    }

    /** Returns how many outcomes inside the window are failures. */
    @Override
    public int failures(long now) {
        evict(now);
        return failures;
    }

    /** Forgets all outcomes. */
    @Override
    public void clear() {
        outcomes.clear();
        failures = 0;
    }
}
