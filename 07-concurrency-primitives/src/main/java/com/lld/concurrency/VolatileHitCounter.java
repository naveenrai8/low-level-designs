package com.lld.concurrency;

/**
 * BUG: {@code volatile} gives visibility and ordering, not atomicity. {@code count++} is
 * read-add-write; two threads read 41, both write 42, one hit is lost.
 *
 * <p>"Add volatile" is the most common wrong fix in code reviews: it fixes stale reads (a flag set
 * by one thread, read by another) and nothing else.
 */
public final class VolatileHitCounter implements HitCounter {

    private volatile long count;

    @Override
    public void hit() {
        count++; // NOT atomic
    }

    @Override
    public long total() {
        return count;
    }
}
