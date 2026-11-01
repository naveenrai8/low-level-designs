package com.lld.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** What makes LFU LFU: lowest access count goes first; ties go to the least recently used. */
class LfuBehaviourTest {

    private final RecordingListener<String, Integer> listener = new RecordingListener<>();
    private final BoundedCache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder()
            .policy(Policy.LFU).maximumSize(3).removalListener(listener, (r, e) -> { }).buildBounded();

    /** Proves LFU evicts the lowest-count key even when it is the newest. */
    @Test
    void lowestFrequencyIsEvictedEvenIfMostRecent() {
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        cache.get("a");
        cache.get("b");                                // c: freq 1, and also the most recently *inserted*

        cache.put("d", 4);

        assertEquals(List.of("c"), listener.keys(RemovalCause.SIZE));
    }

    /** Proves that when counts tie, LFU evicts the least recently used of the tied keys. */
    @Test
    void tieAtTheMinimumGoesToTheLeastRecentlyUsed() {
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        cache.get("a");
        cache.get("b");
        cache.get("c");                                // all freq 2; a is least recent at freq 2

        cache.put("d", 4);

        assertEquals(List.of("a"), listener.keys(RemovalCause.SIZE));
    }

    /** Proves that removing the only key at the lowest count makes the next count the new minimum. */
    @Test
    void removingTheLastMinimumKeyLeavesTheNextBucketAsTheMinimum() {
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        cache.get("b");
        cache.get("c");
        cache.get("c");                                // a:1  b:2  c:3
        cache.remove("a");                             // bucket f=1 is now empty and unlinked
        cache.put("d", 4);                             // d:1
        cache.get("d");
        cache.get("d");
        cache.get("d");                                // d:4  -> minimum is b:2

        cache.put("e", 5);

        assertEquals(List.of("b"), listener.keys(RemovalCause.SIZE));
        assertEquals(Set.of("c", "d", "e"), cache.keySnapshot());
        cache.checkInvariants();
    }

    /** Proves the policy creates buckets as counts grow, skips the excluded key, and ends empty after the last removal. */
    @Test
    void frequencyBucketsAreCreatedAndRemovedOnDemand() {
        LfuPolicy<String> policy = new LfuPolicy<>();
        policy.onInsert("a");
        policy.onInsert("b");
        for (int i = 0; i < 5; i++) {
            policy.onAccess("a");                      // jumps 1 -> 6 through buckets created on demand
        }

        assertEquals(6, policy.frequency("a"));
        assertEquals(1, policy.frequency("b"));
        assertEquals("b", policy.victim(null));
        assertEquals("a", policy.victim("b"));         // excluded key skipped, next bucket used
        policy.onRemove("b");
        assertEquals("a", policy.victim(null));
        policy.onRemove("a");
        assertNull(policy.victim(null));
    }
}
