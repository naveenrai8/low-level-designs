package com.lld.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Proves the bulkhead cap, that a rejected call never runs, and that the permit is always released. */
class BulkheadTest {

    /** A custom Error so the test does not depend on a real JVM failure. */
    private static final class Boom extends Error {
        private static final long serialVersionUID = 1L;
    }

    /** With a cap of 3 and 12 simultaneous callers, the 3 admitted calls meet at a 3-party barrier inside the call, which proves they ran together, and hold their slots until all 9 others have been rejected; no 4th ever runs. */
    @Test
    @Timeout(20)
    void neverMoreThanTheCapRunAtOnce() throws Exception {
        Bulkhead bh = new Bulkhead("db", 3);
        int threads = 12;
        CyclicBarrier inside = new CyclicBarrier(3);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        CountDownLatch rejectedDone = new CountDownLatch(threads - 3);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger ran = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    bh.call(() -> {
                        ran.incrementAndGet();
                        peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                        inside.await(10, TimeUnit.SECONDS);
                        release.await();
                        running.decrementAndGet();
                        return "ok";
                    });
                } catch (BulkheadFullException e) {
                    rejected.incrementAndGet();
                    rejectedDone.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    finished.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }
        start.countDown();
        try {
            assertTrue(rejectedDone.await(10, TimeUnit.SECONDS));
            assertEquals(3, bh.inFlight());
        } finally {
            release.countDown();
        }
        assertTrue(finished.await(15, TimeUnit.SECONDS));
        assertEquals(3, peak.get());
        assertEquals(3, ran.get());
        assertEquals(9, rejected.get());
        assertEquals(9, bh.rejected());
        assertEquals(0, bh.inFlight());
    }

    /** A rejected call is never executed: with the only slot taken, a nested call is refused and its body does not run. */
    @Test
    void rejectedCallNeverRuns() throws Exception {
        Bulkhead bh = new Bulkhead("db", 1);
        AtomicInteger ran = new AtomicInteger();
        bh.call(() -> {
            assertThrows(BulkheadFullException.class, () -> bh.call(() -> ran.incrementAndGet()));
            return "outer";
        });
        assertEquals(0, ran.get());
    }

    /** Whether the call throws an Exception or an Error, the slot comes back, so the bulkhead can be filled again. */
    @Test
    void permitIsReleasedWhenTheCallThrows() {
        Bulkhead bh = new Bulkhead("db", 2);
        for (int i = 0; i < 10; i++) {
            assertThrows(Boom.class, () -> bh.call(() -> {
                throw new Boom();
            }));
            assertThrows(java.io.IOException.class, () -> bh.call(() -> {
                throw new java.io.IOException("x");
            }));
        }
        assertEquals(0, bh.inFlight());
    }

    /** A bad cap or a missing name is refused. */
    @Test
    void capMustBePositive() {
        assertThrows(NullPointerException.class, () -> new Bulkhead(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new Bulkhead("x", 0));
    }
}
