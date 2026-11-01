package com.lld.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** When to pick which, as executable evidence. Neither policy wins every workload. */
class PolicyTradeoffTest {

    /** Builds a count-bounded cache for the given policy. */
    private static BoundedCache<String, Integer> cache(Policy policy, int capacity) {
        return CacheBuilder.<String, Integer>newBuilder().policy(policy).maximumSize(capacity).buildBounded();
    }

    /** A one-off scan (a batch job, a crawler) flushes LRU's hot set. LFU keeps it. */
    @Test
    void aScanFlushesTheLruHotSetButNotTheLfuOne() {
        BoundedCache<String, Integer> lru = cache(Policy.LRU, 4);
        BoundedCache<String, Integer> lfu = cache(Policy.LFU, 4);
        for (BoundedCache<String, Integer> c : List.of(lru, lfu)) {
            hotSet(c);
            for (int i = 0; i < 100; i++) {
                c.put("scan-" + i, i);                 // each scanned key is touched exactly once
            }
        }

        assertFalse(lru.keySnapshot().contains("hot-1"));
        assertFalse(lru.keySnapshot().contains("hot-2"));
        assertTrue(lfu.keySnapshot().containsAll(Set.of("hot-1", "hot-2")));
    }

    /**
     * Cache pollution: yesterday's hot keys keep high counts forever, so after the workload shifts LFU has one usable slot for three new keys and never hits. LRU adapts in one round. Real LFUs age their counts (decay, or W-TinyLFU's periodic halving) for exactly this reason.
     */
    @Test
    void afterAWorkloadShiftLfuStaysPollutedAndLruAdapts() {
        assertEquals(297, newWorkingSetHits(Policy.LRU));   // misses only on the first of 100 rounds
        assertEquals(0, newWorkingSetHits(Policy.LFU));
    }

    /** Runs 100 rounds over three new keys after two old keys were made hot, and returns the number of hits. */
    private static int newWorkingSetHits(Policy policy) {
        BoundedCache<String, Integer> c = cache(policy, 3);
        c.put("old-1", 1);
        c.put("old-2", 2);
        for (int i = 0; i < 100; i++) {
            c.get("old-1");
            c.get("old-2");
        }
        int hits = 0;
        for (int round = 0; round < 100; round++) {
            for (String key : new String[] {"new-1", "new-2", "new-3"}) {
                if (c.get(key).isPresent()) {
                    hits++;
                } else {
                    c.put(key, round);
                }
            }
        }
        return hits;
    }

    /** Makes hot-1 and hot-2 popular by reading each ten times. */
    private static void hotSet(BoundedCache<String, Integer> c) {
        c.put("hot-1", 1);
        c.put("hot-2", 2);
        for (int i = 0; i < 10; i++) {
            c.get("hot-1");
            c.get("hot-2");
        }
    }
}
