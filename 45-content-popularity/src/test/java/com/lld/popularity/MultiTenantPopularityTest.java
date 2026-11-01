package com.lld.popularity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Tests tenant isolation, the tenant cap, the noisy-neighbour policy and concurrent ingest. */
class MultiTenantPopularityTest {

    private static final WindowSpec SPEC = WindowSpec.of(Duration.ofSeconds(10), Duration.ofSeconds(60));

    private final FakeTicker t = new FakeTicker();

    /** Builds a tracker whose tenants use the exact version. */
    private MultiTenantPopularity exact(int max) {
        return new MultiTenantPopularity(() -> new WindowedTopK(t, SPEC), max);
    }

    /** One tenant's items never appear in another tenant's top-K, even with the same item ids. */
    @Test
    void tenantsAreIsolated() {
        MultiTenantPopularity m = exact(10);
        m.record("acme", "secret-a");
        m.record("acme", "shared");
        m.record("beta", "shared");
        assertEquals(List.of("secret-a", "shared"), m.topK("acme", 5).stream().map(Ranked::id).toList());
        assertEquals(List.of(new Ranked("shared", 1)), m.topK("beta", 5));
    }

    /** Reading an unknown tenant returns empty and does not create it. */
    @Test
    void unknownTenantReadDoesNotCreate() {
        MultiTenantPopularity m = exact(1);
        assertEquals(List.of(), m.topK("ghost", 3));
        assertEquals(0, m.tenantCount());
    }

    /** A negative k throws the same way for a known and an unknown tenant. */
    @Test
    void negativeKThrowsForUnknownTenantToo() {
        MultiTenantPopularity m = exact(2);
        m.record("a", "x");
        assertThrows(IllegalArgumentException.class, () -> m.topK("a", -1));
        assertThrows(IllegalArgumentException.class, () -> m.topK("ghost", -1));
    }

    /** Two racing new tenants with a cap of 1: the first is held inside the factory, the second must wait on the creation lock, so exactly one wins and one gets TenantLimitException. */
    @Test
    void racingNewTenantsCannotBothPassTheCap() throws Exception {
        CountDownLatch inFactory = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        MultiTenantPopularity m = new MultiTenantPopularity(() -> {
            if (calls.getAndIncrement() == 0) {
                inFactory.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new WindowedTopK(t, SPEC);
        }, 1);
        AtomicInteger refused = new AtomicInteger();
        Runnable a = () -> m.record("x", "i");
        Runnable b = () -> {
            try {
                m.record("y", "i");
            } catch (TenantLimitException e) {
                refused.incrementAndGet();
            }
        };
        Thread ta = new Thread(a);
        Thread tb = new Thread(b);
        ta.start();
        inFactory.await();
        tb.start();
        while (tb.getState() != Thread.State.BLOCKED && tb.getState() != Thread.State.TERMINATED) {
            Thread.onSpinWait();
        }
        release.countDown();
        ta.join();
        tb.join();
        assertEquals(1, refused.get());
        assertEquals(1, m.tenantCount());
    }

    /** At the tenant cap a new tenant is refused and existing tenants keep working and keep their data. */
    @Test
    void capRefusesNewTenantWithoutEvictingOthers() {
        MultiTenantPopularity m = exact(2);
        m.record("a", "x");
        m.record("b", "y");
        assertThrows(TenantLimitException.class, () -> m.record("c", "z"));
        m.record("a", "x");
        assertEquals(List.of(new Ranked("x", 2)), m.topK("a", 1));
        assertEquals(List.of(new Ranked("y", 1)), m.topK("b", 1));
        assertEquals(2, m.tenantCount());
    }

    /** A noisy tenant that floods 20000 distinct ids stays inside its own fixed-size sketch and leaves a quiet tenant's answer exact. */
    @Test
    void noisyTenantCannotDistortOthers() {
        MultiTenantPopularity m = new MultiTenantPopularity(() -> new SketchWindowedTopK(t, SPEC, 0.01, 0.01, 5, 3), 5);
        m.record("quiet", "q");
        m.record("quiet", "q");
        for (int i = 0; i < 20_000; i++) {
            m.record("noisy", "n" + i);
        }
        assertEquals(List.of(new Ranked("q", 2)), m.topK("quiet", 3));
        assertTrue(m.topK("noisy", 100).size() <= 5);
    }

    /** Many threads, released together by a latch, hammer 4 tenants: every tenant's total is exact. */
    @Test
    void concurrentIngestAcrossTenantsHasExactTotals() throws Exception {
        MultiTenantPopularity m = exact(10);
        int threads = 8;
        int perThread = 5000;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                fs.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    for (int n = 0; n < perThread; n++) {
                        m.record("t" + n % 4, "hot");
                    }
                    return null;
                }));
            }
            ready.await();
            go.countDown();
            for (Future<?> f : fs) {
                f.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        for (int k = 0; k < 4; k++) {
            assertEquals(List.of(new Ranked("hot", (long) threads * perThread / 4)), m.topK("t" + k, 1));
        }
        assertEquals(4, m.tenantCount());
    }
}
