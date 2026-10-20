package com.lld.concurrency;

import java.util.concurrent.atomic.LongAdder;

/**
 * FIX: {@link LongAdder} — atomic, and under heavy write contention it spreads updates across
 * cells instead of having every thread CAS-spin on one variable.
 *
 * <p>Choose {@code AtomicLong} instead when you need {@code compareAndSet} or an exact value read
 * in the same step as the update (e.g. "increment and return the new id"): {@code LongAdder.sum()}
 * is not a snapshot taken atomically with concurrent updates.
 */
public final class LongAdderHitCounter implements HitCounter {

    private final LongAdder count = new LongAdder();

    @Override
    public void hit() {
        count.increment();
    }

    @Override
    public long total() {
        return count.sum();
    }
}
