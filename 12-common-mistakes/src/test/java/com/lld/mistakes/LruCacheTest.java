package com.lld.mistakes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Proves why an LRU {@code get} is a write, that the plain-lock LRU behaves correctly, and that a segmented LRU is not a true LRU.
 */
class LruCacheTest {

    /**
     * Proves on one thread that {@code get} changes an access-ordered map: calling it while iterating fails fast. So a read lock around {@code get} would let two threads relink the same list at once.
     */
    @Test
    void getOnAnAccessOrderedMapIsAStructuralWrite() {
        // Single-threaded proof that "get" mutates: iterating while calling get() fails fast. So a read lock around get() lets two threads relink the same list at once.
        Map<String, Integer> lru = new LinkedHashMap<>(16, 0.75f, true);
        lru.put("a", 1);
        lru.put("b", 2);
        assertThrows(ConcurrentModificationException.class, () -> {
            for (String k : lru.keySet()) {
                lru.get(k);
            }
        });
    }

    /**
     * Proves that reading an entry saves it from eviction, so the entry that was used least recently is the one removed.
     */
    @Test
    void evictsTheLeastRecentlyUsedNotTheLeastRecentlyInserted() {
        LruCache<String, Integer> cache = new LruCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.get("a");                                // a is now most recent
        cache.put("c", 3);                             // evicts b
        assertTrue(cache.containsKey("a"));
        assertFalse(cache.containsKey("b"));
        assertTrue(cache.containsKey("c"));
    }

    /**
     * Proves that 8 threads doing 50,000 mixed gets and puts never corrupt the list and leave the size exactly at capacity.
     */
    @Test
    void staysWithinCapacityUnderConcurrentGetsAndPuts() throws Exception {
        LruCache<Integer, Integer> cache = new LruCache<>(100);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int seed = t;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 50_000; i++) {
                        int key = (i * 31 + seed) % 500;
                        if (i % 3 == 0) {
                            cache.put(key, i);
                        } else {
                            cache.get(key);
                        }
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);           // a corrupted list would surface as an exception
            }
            assertEquals(100, cache.size());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Proves that the segmented cache evicts while it still has room, and evicts a newer entry before the globally oldest one.
     * <p>Capacity 4 in 2 segments of 2. Integer keys hash to themselves, so keys 0, 2 and 4 all land in segment 0.
     */
    @Test
    void segmentedLruEvictsEarlyAndOutOfGlobalOrder() {
        // Capacity 4 in 2 segments of 2. Integer keys hash to themselves: 0, 2, 4 all land in segment 0.
        SegmentedLruCache<Integer, String> segmented = new SegmentedLruCache<>(4, 2);
        LruCache<Integer, String> global = new LruCache<>(4);
        for (int key : new int[] {0, 2, 4}) {
            segmented.put(key, "v" + key);
            global.put(key, "v" + key);
        }
        assertEquals(3, global.size(), "a true LRU of capacity 4 keeps all 3");
        assertEquals(2, segmented.size(), "segment 0 is full at 2 while segment 1 sits empty");
        assertFalse(segmented.containsKey(0));

        // Order: key 1 (segment 1) is the oldest entry overall, but it survives while a newer key in segment 0 is evicted.
        SegmentedLruCache<Integer, String> s = new SegmentedLruCache<>(4, 2);
        s.put(1, "oldest");
        s.put(0, "x");
        s.put(2, "y");
        s.put(4, "newest");                            // evicts 0, not the globally least recent key 1
        assertTrue(s.containsKey(1));
        assertFalse(s.containsKey(0));
    }
}
