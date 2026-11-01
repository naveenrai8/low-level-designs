package com.lld.resilience;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hands out a fixed number of half-open probe slots, never more, even when many threads ask at once.
 *
 * <p>The safe way to take "the last slot" is one atomic step: read the count and write count+1 only if nobody changed it in between (compare-and-set), retrying if somebody did. A plain "if free then take" lets two threads both see the last slot free and both take it. The test fixture {@code RacyProbeGate} is that bug.
 */
final class ProbeGate {

    private final int capacity;
    private final AtomicInteger used = new AtomicInteger();
    private final Runnable gap;

    /**
     * Creates a gate.
     *
     * @param capacity the number of slots, at least 1
     */
    public ProbeGate(int capacity) {
        this(capacity, () -> { });
    }

    /**
     * Creates a gate with a test hook that runs between reading the count and trying to write it.
     *
     * @param capacity the number of slots, at least 1
     * @param gap runs right after the read, so a test can pause two threads at the dangerous spot
     */
    ProbeGate(int capacity, Runnable gap) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.capacity = capacity;
        this.gap = gap;
    }

    /** Tries to take one slot; returns false (and takes nothing) when all are taken. Exactly {@code capacity} callers ever get true. */
    public boolean tryAcquire() {
        while (true) {
            int seen = used.get();
            if (seen >= capacity) {
                return false;
            }
            gap.run();
            if (used.compareAndSet(seen, seen + 1)) {
                return true;
            }
        }
    }

    /**
     * Gives a slot back, for a probe that ended without a verdict (for example a bulkhead rejection).
     *
     * @throws IllegalStateException if no slot is taken; that means a double release, which is a bug and must not be hidden
     */
    public void release() {
        if (used.updateAndGet(u -> u > 0 ? u - 1 : -1) < 0) {
            used.set(0);
            throw new IllegalStateException("release without a matching acquire");
        }
    }

    /** Returns how many slots are taken now. */
    public int used() {
        return used.get();
    }

    /** Makes all slots free again; called when HALF_OPEN starts. */
    public void reset() {
        used.set(0);
    }
}
