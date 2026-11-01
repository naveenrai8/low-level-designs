package com.lld.mistakes;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

/**
 * Proves an optimistic read must be validated. It is single-threaded and deterministic: an optimistic read holds no lock, so a write can run inside the reader's gap on the same thread. That is exactly the interleaving a concurrent writer would produce.
 */
class OptimisticReadTest {

    /**
     * Proves that without {@code validate} the reader returns (1, 20): x from the old write and y from the new one.
     */
    @Test
    void bugSkippingValidateReturnsATornPairThatNeverExisted() {
        Point[] self = new Point[1];
        self[0] = new UnvalidatedOptimisticPoint(() -> self[0].moveTo(10, 20));
        self[0].moveTo(1, 2);

        assertArrayEquals(new double[] {1, 20}, self[0].read(), "x from the old write, y from the new one");
    }

    /**
     * Proves that with {@code validate} the reader notices the write and returns the consistent pair (10, 20).
     */
    @Test
    void fixValidateDetectsTheWriteAndRereadsUnderTheReadLock() {
        Point[] self = new Point[1];
        self[0] = new OptimisticPoint(() -> self[0].moveTo(10, 20));
        self[0].moveTo(1, 2);

        assertArrayEquals(new double[] {10, 20}, self[0].read());
    }

    /**
     * Proves that with no write in between, the optimistic read returns the right pair.
     */
    @Test
    void fixWithoutInterferenceTheOptimisticPathSucceeds() {
        Point p = new OptimisticPoint(Gap.NONE);
        p.moveTo(3, 4);
        assertArrayEquals(new double[] {3, 4}, p.read());
    }
}
