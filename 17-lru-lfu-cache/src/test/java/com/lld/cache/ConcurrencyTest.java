package com.lld.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 8 threads × 50,000 mixed operations behind one start gate, on a cache small enough that most puts evict. The checks are exact <b>conservation laws</b>, not "no exception":
 * <ul>
 *   <li>every {@code get} is exactly one hit or one miss;</li>
 *   <li>every {@code put} either added an entry or replaced one, and every entry that left was reported once, so {@code puts - REPLACED - SIZE - EXPLICIT == size()};</li>
 *   <li>the policy and the map agree, and the weight total hasn't drifted.</li>
 * </ul> A lost update anywhere (an unlocked relink, a double-counted eviction) breaks an equality.
 */
class ConcurrencyTest {

    private static final int THREADS = 8;
    private static final int OPS_PER_THREAD = 50_000;
    private static final int KEYS = 64;
    private static final int CAPACITY = 16;

    /** Proves that after 8 threads hammer the cache, the counts still add up exactly: gets equal hits plus misses, and puts minus removals equal the final size. */
    @ParameterizedTest
    @EnumSource(Policy.class)
    void conservationLawsHoldUnderContention(Policy policy) throws Exception {
        Map<RemovalCause, LongAdder> removals = new EnumMap<>(RemovalCause.class);
        for (RemovalCause cause : RemovalCause.values()) {
            removals.put(cause, new LongAdder());       // populated before any thread starts: read-only after
        }
        BoundedCache<Integer, Integer> cache = CacheBuilder.<Integer, Integer>newBuilder()
                .policy(policy).maximumSize(CAPACITY).ticker(new FakeTicker())
                .removalListener((k, v, cause) -> removals.get(cause).increment(),
                        (r, e) -> { throw new AssertionError(e); })
                .buildBounded();
        LongAdder gets = new LongAdder();
        LongAdder puts = new LongAdder();
        LongAdder removedByCaller = new LongAdder();

        CountDownLatch ready = new CountDownLatch(THREADS);    // every worker is parked at the gate
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                long seed = t;
                futures.add(pool.submit(() -> {
                    SplittableRandom random = new SplittableRandom(seed);
                    ready.countDown();
                    startGate.await();
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        int key = random.nextInt(KEYS);
                        int op = random.nextInt(10);
                        if (op < 5) {
                            cache.get(key);
                            gets.increment();
                        } else if (op < 9) {
                            cache.put(key, i);
                            puts.increment();
                        } else if (cache.remove(key).isPresent()) {
                            removedByCaller.increment();
                        }
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "workers never reached the gate");
            startGate.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        CacheStats stats = cache.stats();
        long replaced = removals.get(RemovalCause.REPLACED).sum();
        long evicted = removals.get(RemovalCause.SIZE).sum();
        long explicit = removals.get(RemovalCause.EXPLICIT).sum();

        assertEquals(gets.sum(), stats.hits() + stats.misses());
        assertEquals(puts.sum() - replaced - evicted - explicit, cache.size());
        assertEquals(stats.evictions(), evicted);
        assertEquals(removedByCaller.sum(), explicit);
        assertEquals(0, removals.get(RemovalCause.EXPIRED).sum());
        assertTrue(cache.size() <= CAPACITY);
        cache.checkInvariants();
    }
}
