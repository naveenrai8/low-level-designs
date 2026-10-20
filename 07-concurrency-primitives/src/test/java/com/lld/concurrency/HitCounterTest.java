package com.lld.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HitCounterTest {

    private static final int THREADS = 8;
    private static final int HITS_PER_THREAD = 100_000;
    private static final long EXPECTED = (long) THREADS * HITS_PER_THREAD;

    /**
     * Probabilistic (the race is inside {@code count++}, where no hook can reach), so it retries.
     * Lost updates show up on the first attempt on any multi-core machine in practice.
     */
    @Test
    void bugVolatileIncrementLosesUpdates() throws Exception {
        long worst = EXPECTED;
        for (int attempt = 0; attempt < 10 && worst == EXPECTED; attempt++) {
            worst = Math.min(worst, hammer(new VolatileHitCounter()));
        }
        assertTrue(worst < EXPECTED, "volatile ++ lost " + (EXPECTED - worst) + " of " + EXPECTED + " hits");
    }

    @Test
    void fixLongAdderCountsEveryHit() throws Exception {
        assertEquals(EXPECTED, hammer(new LongAdderHitCounter()));
    }

    private static long hammer(HitCounter counter) throws Exception {
        Throwable failure = Race.run(THREADS, t -> () -> {
            for (int i = 0; i < HITS_PER_THREAD; i++) {
                counter.hit();
            }
        });
        assertNull(failure);
        return counter.total();
    }
}
