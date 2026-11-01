package com.lld.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Proves the two hit counters behave differently under 8 threads: the volatile one loses hits and the LongAdder one does not.
 */
class HitCounterTest {

    private static final int THREADS = 8;
    private static final int HITS_PER_THREAD = 100_000;
    private static final long EXPECTED = (long) THREADS * HITS_PER_THREAD;

    /**
     * Proves that {@code volatile count++} loses hits when 8 threads hit it together.
     * <p>The race is inside {@code count++}, where no hook can reach, so the test is probabilistic and retries up to 10 times. In practice lost updates show up on the first attempt on any multi-core machine.
     */
    @Test
    void bugVolatileIncrementLosesUpdates() throws Exception {
        long worst = EXPECTED;
        for (int attempt = 0; attempt < 10 && worst == EXPECTED; attempt++) {
            worst = Math.min(worst, hammer(new VolatileHitCounter()));
        }
        assertTrue(worst < EXPECTED, "volatile ++ lost " + (EXPECTED - worst) + " of " + EXPECTED + " hits");
    }

    /**
     * Proves that {@link LongAdderHitCounter} ends with the exact total, with no lost hits.
     */
    @Test
    void fixLongAdderCountsEveryHit() throws Exception {
        assertEquals(EXPECTED, hammer(new LongAdderHitCounter()));
    }

    /**
     * Starts all threads together, each hitting the counter many times, and returns the final total. Fails if any worker threw.
     */
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
