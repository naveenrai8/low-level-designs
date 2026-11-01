package com.lld.resilience;

/**
 * Remembers the most recent call outcomes so the breaker can compute a failure rate.
 *
 * <p>Two shapes exist: {@link CountWindow} keeps the last N outcomes, {@link TimeWindow} keeps the outcomes of the last T nanoseconds. Not thread-safe; the breaker calls it under its lock.
 */
interface SlidingWindow {

    /**
     * Adds one outcome.
     *
     * @param success true for a good call, false for a failure
     * @param now the current ticker reading
     */
    void record(boolean success, long now);

    /** Returns how many outcomes are still inside the window at the given time. */
    int total(long now);

    /** Returns how many of those outcomes are failures. */
    int failures(long now);

    /** Forgets everything. Called on every state change so a new period starts clean. */
    void clear();
}
