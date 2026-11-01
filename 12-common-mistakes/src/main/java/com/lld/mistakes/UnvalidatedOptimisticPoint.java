package com.lld.mistakes;

import java.util.concurrent.locks.StampedLock;

/**
 * <b>Bug (M4).</b> Takes an optimistic stamp but never calls {@code validate}.
 * <p>An optimistic read holds <em>no</em> lock, so a writer can run between reading {@code x} and reading {@code y}. Example: the point is (1, 2), the reader reads x = 1, a writer moves the point to (10, 20), the reader reads y = 20, and returns (1, 20), a pair that never existed.
 * <p>Using the stamp without {@code validate} is the same as having no lock at all.
 */
public final class UnvalidatedOptimisticPoint implements Point {

    private final StampedLock sl = new StampedLock();
    private final Gap gap;
    private double x;
    private double y;

    /**
     * Creates the point at (0, 0). The gap sits between reading x and y so a test can run a write there.
     */
    public UnvalidatedOptimisticPoint(Gap gap) {
        this.gap = gap;
    }

    /**
     * Sets both coordinates under the write lock.
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
     * Reads x and y with an optimistic stamp that it ignores, so it can return a torn pair.
     */
    @Override
    public double[] read() {
        sl.tryOptimisticRead();                        // BUG: stamp ignored
        double cx = x;
        gap.pause();
        double cy = y;
        return new double[] {cx, cy};
    }
}
