package com.lld.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Forces the write-during-rotation interleavings with hooks and latches, then hammers for exact totals. */
class ConcurrencyTest {

    private final FakeTicker ticker = new FakeTicker();

    /** A tiny box so a hook's lambda can reach the metric that is built after it. */
    private static final class MetricHolder {
        RingWindowedMetric metric;
    }

    /**
     * Freezes writer A between "pick slice" and "write", runs {@code duringGap} on the test thread, then lets A finish.
     *
     * <p>Returns the metric so the caller can assert.
     */
    private RingWindowedMetric pauseWriterWhile(Runnable duringGap, long value, MetricHolder holder) throws Exception {
        CountDownLatch picked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean(true);
        Gap gap = () -> {
            if (armed.compareAndSet(true, false)) {
                picked.countDown();
                await(release);
            }
        };
        RingWindowedMetric metric = new RingWindowedMetric(ticker, Duration.ofSeconds(10), 5, gap, Gap.NONE);
        holder.metric = metric;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> writer = pool.submit(() -> metric.record(value));
            assertTrue(picked.await(5, TimeUnit.SECONDS), "writer never reached the gap");
            duringGap.run();
            release.countDown();
            writer.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        return metric;
    }

    /** Waits for a latch with a bounded timeout and fails loudly instead of hanging. */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The writer picked a slice of lap 0, then a full window passes and a newer sample takes that slot. The old sample must NOT land in the new lap; it is dropped from the window and counted. */
    @Test
    void writerPausedAcrossAFullLapNeverPollutesTheNewLap() throws Exception {
        MetricHolder h = new MetricHolder();
        RingWindowedMetric m = pauseWriterWhile(() -> {
            ticker.advanceSeconds(10);
            h.metric.record(5);
        }, 1000, h);
        assertEquals(1, m.count(), "only the new sample is in the window");
        assertEquals(5, m.sum(), "the stale 1000 did not leak into the reset slice");
        assertEquals(1, m.droppedLate());
        assertEquals(2, m.snapshot().lifetimeCount(), "the dropped sample still counts in the lifetime total");
    }

    /** The writer picked epoch 0, time moves on 4 s, and it then writes. The sample must land in epoch 0 (not the current epoch 2): it is visible now, and it expires with epoch 0 at t = 10 s while the newer sample stays. */
    @Test
    void writerPausedWhileTimeMovesOnLandsInItsOwnSlice() throws Exception {
        MetricHolder h = new MetricHolder();
        RingWindowedMetric m = pauseWriterWhile(() -> {
            ticker.advanceSeconds(4);
            h.metric.record(5);
        }, 1000, h);
        assertEquals(2, m.count());
        assertEquals(1005, m.sum());
        assertEquals(0, m.droppedLate());
        ticker.setSeconds(10);
        assertEquals(1, m.count(), "epoch 0 has aged out, and the 1000 went with it");
        assertEquals(5, m.sum());
    }

    /** The writer picked a slice, then the whole window passes with no other writer. The sample is stored in its old slot and a reader correctly ignores it. */
    @Test
    void writerPausedPastTheWindowIsSimplyExpired() throws Exception {
        MetricHolder h = new MetricHolder();
        RingWindowedMetric m = pauseWriterWhile(() -> ticker.advanceSeconds(10), 1000, h);
        assertEquals(0, m.count());
        assertEquals(0, m.sum());
    }

    /** Parks a reset between "relabel the slot" and "clear it", then shows that count(), sum() and snapshot() all block on the slot's lock until the reset finishes, and afterwards see only the new state. */
    @Test
    void readersCannotSeeAHalfDoneReset() throws Exception {
        CountDownLatch inReset = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean(false);
        Gap resetGap = () -> {
            if (armed.get()) {
                inReset.countDown();
                await(release);
            }
        };
        RingWindowedMetric m = new RingWindowedMetric(ticker, Duration.ofSeconds(10), 5, Gap.NONE, resetGap);
        m.record(1000);
        ticker.advanceSeconds(10);
        armed.set(true);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Future<?> writer = pool.submit(() -> m.record(5));
            assertTrue(inReset.await(5, TimeUnit.SECONDS), "reset never started");
            Thread[] readerThreads = new Thread[3];
            long[] seen = new long[3];
            Runnable[] reads = {() -> seen[0] = m.count(), () -> seen[1] = m.sum(), () -> seen[2] = m.snapshot().count()};
            for (int i = 0; i < 3; i++) {
                readerThreads[i] = new Thread(reads[i]);
                readerThreads[i].start();
            }
            for (Thread t : readerThreads) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (t.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertEquals(Thread.State.BLOCKED, t.getState(), "a reader got through while the reset was half done");
            }
            release.countDown();
            writer.get(5, TimeUnit.SECONDS);
            for (Thread t : readerThreads) {
                t.join(5000);
            }
            assertEquals(1, seen[0]);
            assertEquals(5, seen[1]);
            assertEquals(1, seen[2]);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** Many threads released together by a start gate record into a frozen clock; every count, the sum and the histogram total must be exact. */
    @Test
    void hammerGivesExactTotals() throws Exception {
        int threads = 8;
        int perThread = 25_000;
        RingWindowedMetric metric = new RingWindowedMetric(ticker, Duration.ofSeconds(10), 5);
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            long value = t + 1;
            futures.add(pool.submit(() -> {
                gate.await();
                for (int i = 0; i < perThread; i++) {
                    metric.record(value);
                }
                return null;
            }));
        }
        gate.countDown();
        for (Future<?> f : futures) {
            assertNull(f.get(20, TimeUnit.SECONDS));
        }
        pool.shutdown();
        long expectedSum = 0;
        for (int t = 1; t <= threads; t++) {
            expectedSum += (long) t * perThread;
        }
        MetricSnapshot s = metric.snapshot();
        assertEquals((long) threads * perThread, s.count());
        assertEquals(expectedSum, s.sum());
        assertEquals(s.count(), s.lifetimeCount());
        assertEquals(expectedSum, s.lifetimeSum());
        for (int t = 1; t <= threads; t++) {
            assertEquals(perThread, s.binCounts()[t]);
        }
    }

    /** Several rounds of gated writers with the clock moved between rounds (not during them). After each round the window holds exactly the newest rounds that are still live, and nothing is dropped. */
    @Test
    void hammerAcrossRoundsCountsExactly() throws Exception {
        int threads = 4;
        int rounds = 20;
        int perRound = 500;
        RingWindowedMetric metric = new RingWindowedMetric(ticker, Duration.ofSeconds(10), 5);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int round = 0; round < rounds; round++) {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    for (int i = 0; i < perRound; i++) {
                        metric.record(1);
                    }
                    return null;
                }));
            }
            gate.countDown();
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
            assertEquals(Math.min(round + 1, 5) * (long) threads * perRound, metric.count(), "round " + round);
            ticker.advanceSeconds(2);
        }
        pool.shutdown();
        assertEquals(0, metric.droppedLate());
        assertEquals((long) rounds * threads * perRound, metric.snapshot().lifetimeCount());
    }
}
