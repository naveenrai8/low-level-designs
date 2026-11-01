package com.lld.concurrency;

import java.util.concurrent.atomic.LongAdder;

/**
 * FIX: use {@link LongAdder}. It is atomic, and under heavy write contention it spreads updates over several cells instead of making every thread spin on one variable.
 * <p>Choose {@code AtomicLong} instead when you need {@code compareAndSet}, or an exact value in the same step as the update (for example "increment and return the new id"). {@code LongAdder.sum()} is not an atomic snapshot taken together with concurrent updates.
 */
public final class LongAdderHitCounter implements HitCounter {

    private final LongAdder count = new LongAdder();

    /**
     * Adds one to the count without losing updates from other threads.
     */
    @Override
    public void hit() {
        count.increment();
    }

    /**
     * Adds up all the cells. Exact once the writers have stopped.
     */
    @Override
    public long total() {
        return count.sum();
    }
}
