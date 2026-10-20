package com.lld.mistakes;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

/**
 * Single-threaded and deterministic. An optimistic read holds no lock, so a write can run inside
 * the reader's gap on the same thread. That's exactly the interleaving a concurrent writer would
 * produce.
 */
class OptimisticReadTest {

    @Test
    void bugSkippingValidateReturnsATornPairThatNeverExisted() {
        Point[] self = new Point[1];
        self[0] = new UnvalidatedOptimisticPoint(() -> self[0].moveTo(10, 20));
        self[0].moveTo(1, 2);

        assertArrayEquals(new double[] {1, 20}, self[0].read(), "x from the old write, y from the new one");
    }

    @Test
    void fixValidateDetectsTheWriteAndRereadsUnderTheReadLock() {
        Point[] self = new Point[1];
        self[0] = new OptimisticPoint(() -> self[0].moveTo(10, 20));
        self[0].moveTo(1, 2);

        assertArrayEquals(new double[] {10, 20}, self[0].read());
    }

    @Test
    void fixWithoutInterferenceTheOptimisticPathSucceeds() {
        Point p = new OptimisticPoint(Gap.NONE);
        p.moveTo(3, 4);
        assertArrayEquals(new double[] {3, 4}, p.read());
    }
}
