package com.lld.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * When does the LeetCode 460 {@code minFreq} trick break? Not where you'd first guess.
 *
 * <ul>
 *   <li><b>Count bound + remove: still correct.</b> A remove leaves size below capacity, so the next insert can't evict, and that insert resets {@code minFreq = 1} before any eviction can read the stale value. 100k random ops agree with the oracle.</li>
 *   <li><b>Weight bound: broken.</b> One insert may need eviction right after a remove, or an update may need a victim other than itself. Both read a stale or too-narrow
 *       {@code minFreq} and find nothing to evict, though the cache is over its bound.</li>
 * </ul>
 */
class MinFreqCounterLfuTest {

    /** Proves the minFreq trick still matches the oracle under a count bound, because the next insert resets minFreq. */
    @Test
    void underACountBoundRemovalsDoNotExposeTheStaleCounter() {
        BoundedCache<Integer, Integer> cache = new BoundedCache<>(new MinFreqCounterLfuPolicy<>(),
                DifferentialTest.CAPACITY, (k, v) -> 1, 0, new FakeTicker(), (k, v, c) -> { }, (r, e) -> { });

        DifferentialTest.run(cache, new ReferenceModels.Lfu(DifferentialTest.CAPACITY), 7L);
    }

    /** Proves the minFreq trick fails under a weight bound after a remove, while the bucket list evicts correctly. */
    @Test
    void underAWeightBoundAnInsertAfterRemoveFindsNothingToEvict() {
        // a:1  b:2  c:2, then remove a: bucket 1 is empty but minFreq is still 1.
        BoundedCache<String, String> trick = weighted(new MinFreqCounterLfuPolicy<>());
        BoundedCache<String, String> buckets = weighted(new LfuPolicy<>());
        for (BoundedCache<String, String> cache : List.of(trick, buckets)) {
            cache.put("a", "xxx");
            cache.put("b", "xxx");
            cache.put("c", "xxx");
            cache.get("b");
            cache.get("c");
            cache.remove("a");
        }

        assertThrows(IllegalStateException.class, () -> trick.put("d", "xxxxx"));   // 6 + 5 > 10, victim: none

        buckets.put("d", "xxxxx");                                                  // evicts b (freq 2, least recent)
        assertEquals(Set.of("c", "d"), buckets.keySnapshot());
        buckets.checkInvariants();
    }

    /** Proves the minFreq trick finds no victim when a growing update is the only key at the minimum, while the bucket list works. */
    @Test
    void underAWeightBoundAGrowingUpdateFindsNoVictimButItself() {
        // a:1  b:4  c:4. Updating a moves it to freq 2 and minFreq to 2: bucket 2 holds only a, which is excluded, so the trick sees no victim although b and c are evictable.
        BoundedCache<String, String> trick = weighted(new MinFreqCounterLfuPolicy<>());
        BoundedCache<String, String> buckets = weighted(new LfuPolicy<>());
        for (BoundedCache<String, String> cache : List.of(trick, buckets)) {
            cache.put("a", "xxx");
            cache.put("b", "xxx");
            cache.put("c", "xxx");
            for (int i = 0; i < 3; i++) {
                cache.get("b");
                cache.get("c");
            }
        }

        assertThrows(IllegalStateException.class, () -> trick.put("a", "xxxxxx"));  // 3 + 3 + 6 > 10

        buckets.put("a", "xxxxxx");
        assertEquals(Set.of("a", "c"), buckets.keySnapshot());
        buckets.checkInvariants();
    }

    /**
     * The simplest weight-bound failure, no remove needed: one insert that needs two victims. The first eviction empties bucket 1, {@code minFreq} stays 1, and the second {@code victim()} reads the empty bucket.
     */
    @Test
    void underAWeightBoundOneInsertNeedingTwoVictimsFindsTheSecondBucketEmpty() {
        BoundedCache<String, String> trick = weighted(new MinFreqCounterLfuPolicy<>());
        BoundedCache<String, String> buckets = weighted(new LfuPolicy<>());
        for (BoundedCache<String, String> cache : List.of(trick, buckets)) {
            cache.put("a", "xxx");
            cache.put("b", "xxx");
            cache.put("c", "xxx");
            cache.get("b");
            cache.get("c");                                                     // a:1  b:2  c:2
        }

        assertThrows(IllegalStateException.class, () -> trick.put("d", "x".repeat(9)));  // evicts a, then finds nothing

        buckets.put("d", "x".repeat(9));                                            // needs 3 victims: a, b, c
        assertEquals(Set.of("d"), buckets.keySnapshot());
        buckets.checkInvariants();
    }

    /** Builds a cache of weight 10 where each value weighs its length, using the given policy. */
    private static BoundedCache<String, String> weighted(EvictionPolicy<String> policy) {
        return new BoundedCache<>(policy, 10, (k, v) -> v.length(), 0, new FakeTicker(),
                (k, v, c) -> { }, (r, e) -> { });
    }
}
