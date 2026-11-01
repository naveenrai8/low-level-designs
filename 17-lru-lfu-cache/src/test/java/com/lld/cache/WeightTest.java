package com.lld.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** maximumWeight: one insert can evict many; an entry can be too big to store at all. */
class WeightTest {

    private final RecordingListener<String, String> listener = new RecordingListener<>();

    /** Builds a cache with weight bound 10 where each value weighs its length. */
    private BoundedCache<String, String> cache(Policy policy) {
        return CacheBuilder.<String, String>newBuilder().policy(policy)
                .maximumWeight(10, (k, v) -> v.length())
                .removalListener(listener, (r, e) -> { }).buildBounded();
    }

    /** Proves one heavy insert evicts as many entries as it needs. */
    @ParameterizedTest
    @EnumSource(Policy.class)
    void oneHeavyInsertEvictsSeveralVictims(Policy policy) {
        BoundedCache<String, String> cache = cache(policy);
        cache.put("a", "xxx");
        cache.put("b", "xxx");
        cache.put("c", "xxx");                          // 9 of 10

        cache.put("d", "x".repeat(9));                  // needs 9: a, b and c must all go

        assertEquals(List.of("a", "b", "c"), listener.keys(RemovalCause.SIZE));
        assertEquals(Set.of("d"), cache.keySnapshot());
        cache.checkInvariants();
    }

    /** Proves an entry heavier than the whole bound is refused and the cache keeps its other entries. */
    @ParameterizedTest
    @EnumSource(Policy.class)
    void anEntryHeavierThanTheBoundIsRefusedWithoutFlushingTheCache(Policy policy) {
        BoundedCache<String, String> cache = cache(policy);
        cache.put("a", "xxx");

        cache.put("huge", "x".repeat(11));              // returns normally: data-dependent, not a caller bug

        assertEquals(Set.of("a"), cache.keySnapshot());
        assertEquals(List.of(new Removal<>("huge", "x".repeat(11), RemovalCause.SIZE)), listener.events);
        assertEquals(1, cache.stats().evictions());
    }

    /** Proves an LRU update that grows evicts other keys and keeps the one being written. */
    @Test
    void lruUpdateThatGrowsEvictsOthersNeverTheKeyBeingWritten() {
        BoundedCache<String, String> cache = cache(Policy.LRU);
        cache.put("a", "xxx");
        cache.put("b", "xxx");
        cache.put("c", "xxx");

        cache.put("a", "x".repeat(8));                  // 3 + 3 + 8 = 14 > 10

        assertEquals(List.of("b", "c"), listener.keys(RemovalCause.SIZE));
        assertEquals(Optional.of("x".repeat(8)), cache.get("a"));
        cache.checkInvariants();
    }

    /**
     * In LFU the key being written can be the lowest-frequency entry even after its access: here a has count 2 while b and c have 4. Without {@code victim(exclude)} the cache would evict the value it was asked to store.
     */
    @Test
    void lfuUpdateThatGrowsEvictsOthersNeverTheKeyBeingWritten() {
        BoundedCache<String, String> cache = cache(Policy.LFU);
        cache.put("a", "xxx");
        cache.put("b", "xxx");
        cache.put("c", "xxx");
        for (int i = 0; i < 3; i++) {
            cache.get("b");
            cache.get("c");
        }

        cache.put("a", "x".repeat(6));                  // 3 + 3 + 6 = 12 > 10: one victim, b (least recent at count 4)

        assertEquals(List.of("b"), listener.keys(RemovalCause.SIZE));
        assertEquals(Set.of("a", "c"), cache.keySnapshot());
        cache.checkInvariants();
    }

    /** Proves a shrinking update gives its weight back, so a later insert fits. */
    @Test
    void weightAccountingSurvivesShrinkingUpdates() {
        BoundedCache<String, String> cache = cache(Policy.LRU);
        cache.put("a", "x".repeat(9));
        cache.put("a", "x");                            // shrink: 9 -> 1
        cache.put("b", "x".repeat(9));                  // fits only if the old 9 was subtracted

        assertEquals(Set.of("a", "b"), cache.keySnapshot());
        cache.checkInvariants();
    }

    /** Proves a weigher that returns a negative number makes put throw and stores nothing. */
    @Test
    void aNegativeWeightIsRejected() {
        Cache<String, String> cache = CacheBuilder.<String, String>newBuilder()
                .maximumWeight(10, (k, v) -> -1).build();

        assertThrows(IllegalArgumentException.class, () -> cache.put("a", "x"));
        assertEquals(0, cache.size());
    }

    /** An oversized update refuses the new value and drops the old one: REPLACED, then SIZE. */
    @ParameterizedTest
    @EnumSource(Policy.class)
    void anOversizedUpdateDropsTheOldValueToo(Policy policy) {
        BoundedCache<String, String> cache = cache(policy);
        cache.put("a", "xxx");

        cache.put("a", "x".repeat(11));

        assertEquals(List.of(new Removal<>("a", "xxx", RemovalCause.REPLACED),
                new Removal<>("a", "x".repeat(11), RemovalCause.SIZE)), listener.events);
        assertEquals(Set.of(), cache.keySnapshot());
        assertEquals(1, cache.stats().evictions());
        cache.checkInvariants();
    }

    /**
     * {@code totalWeight + incoming > maxWeight} overflows to a negative number near
     * {@code Long.MAX_VALUE}, so nothing is evicted and the bound is silently broken.
     * {@code incoming > maxWeight - totalWeight} can't overflow, because 0 <= total <= max.
     */
    @Test
    void anInsertNearLongMaxValueStillEvicts() {
        BoundedCache<String, Long> cache = huge();
        cache.put("a", Long.MAX_VALUE - 1);

        cache.put("b", 2L);                             // MAX - 1 + 2 wraps negative

        assertEquals(Set.of("b"), cache.keySnapshot());
        cache.checkInvariants();
    }

    /** Same overflow on the update path: the old weight is subtracted before the growth is checked. */
    @Test
    void aGrowingUpdateNearLongMaxValueStillEvicts() {
        BoundedCache<String, Long> cache = huge();
        cache.put("a", Long.MAX_VALUE - 1);
        cache.put("b", 1L);                             // exactly MAX: fits

        cache.put("b", 3L);                             // MAX + 2 would wrap negative

        assertEquals(Set.of("b"), cache.keySnapshot());
        assertEquals(Optional.of(3L), cache.get("b"));
        cache.checkInvariants();
    }

    /** Builds a cache whose bound is Long.MAX_VALUE and whose weight is the value itself. */
    private static BoundedCache<String, Long> huge() {
        return CacheBuilder.<String, Long>newBuilder()
                .maximumWeight(Long.MAX_VALUE, (k, v) -> v).buildBounded();
    }
}
