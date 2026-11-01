package com.lld.blockingqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Risk 5: exact totals when many producers push many items through the queue and the pool, all released at the same instant by a start-gate latch. */
class PoolConcurrencyTest {

    private static final int PRODUCERS = 8;
    private static final int ITEMS = 2_000;
    private static final ThreadLocal<Boolean> PRODUCER = new ThreadLocal<>();

    /** Proves 8 producers x 2,000 tasks through a pool with caller-runs backpressure (tiny queue) execute exactly 16,000 tasks, none lost, none twice. */
    @Test
    void everySubmittedTaskRunsExactlyOnce() throws Exception {
        FixedThreadPool pool = new FixedThreadPool(4, 8, RejectionPolicies.callerRuns(), Ticker.system(), WaitLoopTest::daemon);
        AtomicLong executed = new AtomicLong();
        AtomicLong sum = new AtomicLong();
        AtomicLong byCallers = new AtomicLong();
        AtomicLong byWorkers = new AtomicLong();
        runProducers(index -> {
            for (int i = 1; i <= ITEMS; i++) {
                long value = i;
                pool.execute(() -> {
                    (PRODUCER.get() != null ? byCallers : byWorkers).incrementAndGet();
                    executed.incrementAndGet();
                    sum.addAndGet(value);
                });
            }
        });
        pool.shutdown();

        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "the pool did not terminate");
        assertEquals((long) PRODUCERS * ITEMS, executed.get());
        assertEquals((long) PRODUCERS * ITEMS, byWorkers.get() + byCallers.get());
        assertTrue(byWorkers.get() > 0, "no task ever ran on a worker: every offer was refused");
        assertEquals(PRODUCERS * ((long) ITEMS * (ITEMS + 1) / 2), sum.get());
    }

    /** Proves the raw queue (capacity 3, so producers constantly block) delivers 16,000 distinct items to 4 consumers with an exact total and no duplicates. */
    @Test
    void queueDeliversEveryItemExactlyOnceUnderContention() throws Exception {
        BoundedBlockingQueue<Long> q = new BoundedBlockingQueue<>(3);
        int consumers = 4;
        long[] sums = new long[consumers];
        int[] counts = new int[consumers];
        List<Thread> threads = new ArrayList<>();
        for (int c = 0; c < consumers; c++) {
            int id = c;
            Thread t = WaitLoopTest.daemon(() -> {
                try {
                    while (true) {
                        long v = q.take();
                        sums[id] += v;
                        counts[id]++;
                    }
                } catch (QueueClosedException done) {
                    // closed and empty: finished
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(t);
            t.start();
        }
        runProducers(index -> {
            try {
                for (int i = 0; i < ITEMS; i++) {
                    q.put((long) index * ITEMS + i); // every item has a unique value
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        q.close();
        for (Thread t : threads) {
            t.join(30_000);
            assertFalse(t.isAlive(), "a consumer never finished: close() did not wake it");
        }

        long total = (long) PRODUCERS * ITEMS;
        long count = 0;
        long sum = 0;
        for (int c = 0; c < consumers; c++) {
            count += counts[c];
            sum += sums[c];
        }
        assertEquals(total, count);
        assertEquals(total * (total - 1) / 2, sum); // values are 0..total-1, each exactly once
    }

    /** What one producer thread does. */
    private interface Producer {
        /** Runs the work of producer number {@code index}. */
        void run(int index);
    }

    /** Starts all producers behind one gate so they begin at the same moment, and returns when all are done. */
    private static void runProducers(Producer producer) throws InterruptedException {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(PRODUCERS);
        for (int p = 0; p < PRODUCERS; p++) {
            int index = p;
            WaitLoopTest.daemon(() -> {
                try {
                    PRODUCER.set(true); // lets a task tell whether it is running on a producer (caller-runs) or on a worker
                    gate.await();
                    producer.run(index);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        gate.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
    }
}
