package com.lld.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** F2: the registry never holds more than maxKeys series, and nothing recorded is lost. */
class KeyedMetricsTest {

    private final FakeTicker ticker = new FakeTicker();

    /** Builds a registry with a cap of {@code cap} keys. */
    private KeyedMetrics registry(int cap) {
        return new KeyedMetrics(ticker, Duration.ofSeconds(10), 5, cap);
    }

    /** Keys beyond the cap share one overflow series and its count is exact. */
    @Test
    void capAndExactOverflowCounts() {
        KeyedMetrics reg = registry(3);
        for (int k = 0; k < 10; k++) {
            for (int i = 0; i <= k; i++) {
                reg.record("/ep" + k, 1);
            }
        }
        assertEquals(3, reg.size());
        RegistrySnapshot s = reg.snapshotAll();
        assertEquals(1, s.metrics().get("/ep0").count());
        assertEquals(2, s.metrics().get("/ep1").count());
        assertEquals(3, s.metrics().get("/ep2").count());
        long expectedOverflow = 0;
        for (int k = 3; k < 10; k++) {
            expectedOverflow += k + 1;
        }
        assertEquals(expectedOverflow, s.metrics().get(KeyedMetrics.OVERFLOW_KEY).count());
        assertEquals(4, s.metrics().size(), "three keys plus the overflow series");
    }

    /** A key that got its own series keeps it after the cap is full. */
    @Test
    void existingKeyKeepsItsSeries() {
        KeyedMetrics reg = registry(1);
        WindowedMetric first = reg.metric("a");
        reg.metric("b");
        assertSame(first, reg.metric("a"));
    }

    /** Asking for the overflow key by name returns the overflow series and does not use up a slot. */
    @Test
    void overflowKeyIsReserved() {
        KeyedMetrics reg = registry(1);
        reg.record(KeyedMetrics.OVERFLOW_KEY, 1);
        assertEquals(0, reg.size());
        reg.record("a", 1);
        assertEquals(1, reg.size());
    }

    /** A null key and a zero cap are rejected. */
    @Test
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> registry(1).metric(null));
        assertThrows(IllegalArgumentException.class, () -> registry(0));
    }

    /** Once the cap is full, an overflow lookup must not take the registration lock: another thread holds it, and the lookup still returns, and the lock-entry counter does not move. */
    @Test
    void overflowLookupDoesNotTakeTheLock() throws Exception {
        KeyedMetrics reg = registry(1);
        reg.record("a", 1);
        long before = reg.registrationLockEntries();
        for (int i = 0; i < 100; i++) {
            reg.record("extra" + i, 1);
        }
        assertEquals(before, reg.registrationLockEntries());
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (reg.registrationLockForTest()) {
                holding.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        holder.start();
        try {
            assertTrue(holding.await(5, TimeUnit.SECONDS));
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<WindowedMetric> f = pool.submit(() -> reg.metric("another-overflow-key"));
                assertSame(reg.metric(KeyedMetrics.OVERFLOW_KEY), f.get(2, TimeUnit.SECONDS));
            } finally {
                pool.shutdownNow();
            }
        } finally {
            release.countDown();
            holder.join(5000);
        }
    }

    /** Forces the race: two threads both miss lock-free on the same new key, and both are parked just before the lock. They must still end up with the same instance. */
    @Test
    void forcedFirstRegistrationRaceGivesOneInstance() throws Exception {
        CountDownLatch bothMissed = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Gap gap = () -> {
            bothMissed.countDown();
            try {
                go.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        KeyedMetrics reg = new KeyedMetrics(ticker, Duration.ofSeconds(10), 5, 10, gap);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<WindowedMetric> a = pool.submit(() -> reg.metric("/same"));
            Future<WindowedMetric> b = pool.submit(() -> reg.metric("/same"));
            assertTrue(bothMissed.await(5, TimeUnit.SECONDS), "both threads should miss before either registers");
            go.countDown();
            assertSame(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
            assertEquals(1, reg.size());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Many threads asking for the same new key at the same instant all get the very same instance. */
    @Test
    void concurrentFirstRegistrationGivesOneInstance() throws Exception {
        KeyedMetrics reg = registry(10);
        int threads = 16;
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<WindowedMetric>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return reg.metric("/same");
            }));
        }
        gate.countDown();
        Set<WindowedMetric> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Future<WindowedMetric> f : futures) {
            seen.add(f.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertEquals(1, seen.size());
        assertEquals(1, reg.size());
    }

    /** Many threads racing to register many distinct keys never exceed the cap and never lose a sample. */
    @Test
    void concurrentRegistrationRespectsCapAndLosesNothing() throws Exception {
        int cap = 20;
        int threads = 8;
        int keys = 200;
        KeyedMetrics reg = registry(cap);
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                gate.await();
                for (int k = 0; k < keys; k++) {
                    reg.record("k" + k, 1);
                }
                return null;
            }));
        }
        gate.countDown();
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(cap, reg.size());
        long total = 0;
        for (MetricSnapshot m : reg.snapshotAll().metrics().values()) {
            total += m.count();
        }
        assertEquals((long) threads * keys, total);
        assertTrue(reg.snapshotAll().metrics().size() <= cap + 1);
    }
}
