package com.lld.mistakes;

import java.util.concurrent.locks.StampedLock;

/**
 * <b>Fix (M4).</b> The {@code StampedLock} Javadoc pattern: copy the fields into locals, then {@code validate}, and fall back to a real read lock if a write happened in between. Only use the locals after validating.
 * <p>It fits when reads vastly outnumber writes <em>and</em> a read does not change anything, which is exactly why it does not fit an LRU {@code get} (M2).
 */
public final class OptimisticPoint implements Point {

    private final StampedLock sl = new StampedLock();
    private final Gap gap;
    private double x;
    private double y;

    /**
     * Creates the point at (0, 0). The gap sits between reading x and y so a test can run a write there.
     */
    public OptimisticPoint(Gap gap) {
        this.gap = gap;
    }

    /**
     * Sets both coordinates under the write lock, so a writer never overlaps another writer or a fallback reader.
     */
    @Override
    public void moveTo(double newX, double newY) {
        long stamp = sl.writeLock();
        try {
            x = newX;
            y = newY;
        } finally {
            sl.unlockWrite(stamp);
        }
    }

    /**
     * Reads x and y optimistically with no lock. If a write happened meanwhile, validation fails and it re-reads both under the read lock. Always returns a pair that existed at one moment.
     */
    @Override
    public double[] read() {
        long stamp = sl.tryOptimisticRead();
        double cx = x;
        gap.pause();
        double cy = y;
        if (!sl.validate(stamp)) {                     // a write happened: copies may be torn
            stamp = sl.readLock();
            try {
                cx = x;
                cy = y;
            } finally {
                sl.unlockRead(stamp);
            }
        }
        return new double[] {cx, cy};
    }
}
