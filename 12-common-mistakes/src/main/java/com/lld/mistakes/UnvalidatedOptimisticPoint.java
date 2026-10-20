package com.lld.mistakes;

import java.util.concurrent.locks.StampedLock;

/**
 * <b>Bug (M4).</b> Takes an optimistic stamp but never calls {@code validate}. An optimistic read
 * holds <em>no</em> lock, so a writer can run between reading {@code x} and reading {@code y} and
 * the caller gets a torn pair that never existed. Using the stamp without {@code validate} is the
 * same as having no lock at all.
 */
public final class UnvalidatedOptimisticPoint implements Point {

    private final StampedLock sl = new StampedLock();
    private final Gap gap;
    private double x;
    private double y;

    public UnvalidatedOptimisticPoint(Gap gap) {
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
        sl.tryOptimisticRead();                        // BUG: stamp ignored
        double cx = x;
        gap.pause();
        double cy = y;
        return new double[] {cx, cy};
    }
}
