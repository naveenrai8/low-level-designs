package com.lld.concurrency;

/**
 * BUG: {@code volatile} gives visibility and ordering, not atomicity.
 * <p>Example: {@code count++} is three steps (read, add, write). Thread A reads 41, thread B reads 41, both write 42, and one hit is lost.
 * <p>"Add volatile" is the most common wrong fix in code reviews. It fixes stale reads (a flag set by one thread and read by another) and nothing else.
 */
public final class VolatileHitCounter implements HitCounter {

    private volatile long count;

    /**
     * Adds one to the count. Not atomic, so concurrent calls can overwrite each other and lose hits.
     */
    @Override
    public void hit() {
        count++; // NOT atomic
    }

    /**
     * Returns the current count. Always sees the latest write, but the writes themselves may have lost hits.
     */
    @Override
    public long total() {
        return count;
    }
}
