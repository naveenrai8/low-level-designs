package com.lld.mistakes;

import java.util.concurrent.locks.StampedLock;

/**
 * <b>Fix (M4).</b> The {@code StampedLock} Javadoc pattern: copy fields into locals, then
 * {@code validate}, and fall back to a real read lock if a write happened in between. Only use the
 * locals after validating. It fits when reads vastly outnumber writes <em>and</em> a read doesn't
 * mutate anything, which is exactly why it doesn't fit an LRU {@code get} (M2).
 */
public final class OptimisticPoint implements Point {

    private final StampedLock sl = new StampedLock();
    private final Gap gap;
    private double x;
    private double y;

    public OptimisticPoint(Gap gap) {
        this.gap = gap;
    }

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
