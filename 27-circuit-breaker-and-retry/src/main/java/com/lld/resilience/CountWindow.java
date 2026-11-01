package com.lld.resilience;

/** A window of the last N outcomes. The N+1th outcome pushes the oldest one out. */
final class CountWindow implements SlidingWindow {

    private final boolean[] ring;
    private int next;
    private int total;
    private int failures;

    /**
     * Creates a window.
     *
     * @param size how many outcomes to remember, at least 1
     */
    public CountWindow(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("size must be at least 1");
        }
        this.ring = new boolean[size];
    }

    /** Adds an outcome, overwriting the oldest one when the ring is full and fixing the failure count to match. */
    @Override
    public void record(boolean success, long now) {
        if (total == ring.length && !ring[next]) {
            failures--;
        }
        if (total < ring.length) {
            total++;
        }
        ring[next] = success;
        if (!success) {
            failures++;
        }
        next = (next + 1) % ring.length;
    }

    /** Returns how many outcomes are held; the time argument is ignored. */
    @Override
    public int total(long now) {
        return total;
    }

    /** Returns how many held outcomes are failures; the time argument is ignored. */
    @Override
    public int failures(long now) {
        return failures;
    }

    /** Forgets all outcomes. */
    @Override
    public void clear() {
        next = 0;
        total = 0;
        failures = 0;
    }
}
