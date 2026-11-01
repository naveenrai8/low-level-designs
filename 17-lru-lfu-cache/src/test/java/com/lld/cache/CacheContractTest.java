package com.lld.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Behaviour both policies share. Written once, run against LRU and LFU. */
class CacheContractTest {

    /** Builds a small test cache with the given policy and capacity. The error handler fails the test if the listener ever throws. */
    private static BoundedCache<String, Integer> cache(Policy policy, int capacity, RecordingListener<String, Integer> l) {
        return CacheBuilder.<String, Integer>newBuilder().policy(policy).maximumSize(capacity)
                .removalListener(l, (r, e) -> { throw new AssertionError(e); }).buildBounded();
    }

    /** Proves a lookup before the put counts as a miss and the one after counts as a hit, for both policies. */
    @ParameterizedTest
    @EnumSource(Policy.class)
    void missThenHitAreCounted(Policy policy) {
        BoundedCache<String, Integer> cache = cache(policy, 2, new RecordingListener<>());

        assertEquals(Optional.empty(), cache.get("a"));
        cache.put("a", 1);
        assertEquals(Optional.of(1), cache.get("a"));

        assertEquals(new CacheStats(1, 1, 0, 0), cache.stats());
    }

    /** Proves the cache never holds more than its capacity, and that each overflow is reported once as a SIZE removal. */
    @ParameterizedTest
    @EnumSource(Policy.class)
    void neverExceedsCapacity(Policy policy) {
        RecordingListener<String, Integer> listener = new RecordingListener<>();
        BoundedCache<String, Integer> cache = cache(policy, 3, listener);

        for (int i = 0; i < 10; i++) {
            cache.put("k" + i, i);
            assertTrue(cache.size() <= 3);
            cache.checkInvariants();
        }

        assertEquals(3, cache.size());
        assertEquals(7, cache.stats().evictions());
        assertEquals(7, listener.keys(RemovalCause.SIZE).size());
    }

    /** Proves remove returns the old value once, then empty, and reports an EXPLICIT removal. */
    @ParameterizedTest
    @EnumSource(Policy.class)
    void removeReturnsTheValueAndReportsExplicit(Policy policy) {
        RecordingListener<String, Integer> listener = new RecordingListener<>();
        BoundedCache<String, Integer> cache = cache(policy, 2, listener);
        cache.put("a", 1);

        assertEquals(Optional.of(1), cache.remove("a"));
        assertEquals(Optional.empty(), cache.remove("a"));

        assertEquals(0, cache.size());
        assertEquals(List.of(new Removal<>("a", 1, RemovalCause.EXPLICIT)), listener.events);
        cache.checkInvariants();
    }

    /** Proves writing an existing key swaps the value, keeps the size at one, and reports the old value as REPLACED. */
    @ParameterizedTest
    @EnumSource(Policy.class)
    void updateReplacesTheValueWithoutGrowing(Policy policy) {
        RecordingListener<String, Integer> listener = new RecordingListener<>();
        BoundedCache<String, Integer> cache = cache(policy, 2, listener);
        cache.put("a", 1);
        cache.put("a", 2);

        assertEquals(1, cache.size());
        assertEquals(Optional.of(2), cache.get("a"));
        assertEquals(List.of(new Removal<>("a", 1, RemovalCause.REPLACED)), listener.events);
    }

    /**
     * Evict BEFORE insert. With insert-then-evict, LFU picks the new key itself: it is the only key at frequency 1. LRU is immune, but the contract holds for both.
     */
    @ParameterizedTest
    @EnumSource(Policy.class)
    void aNewKeyIsNeverEvictedByItsOwnInsert(Policy policy) {
        BoundedCache<String, Integer> cache = cache(policy, 2, new RecordingListener<>());
        cache.put("a", 1);
        cache.get("a");
        cache.put("b", 2);
        cache.get("b");

        cache.put("c", 3);

        assertTrue(cache.keySnapshot().contains("c"));
        assertEquals(2, cache.size());
    }

    /** Proves null keys and null values throw NullPointerException. */
    @ParameterizedTest
    @EnumSource(Policy.class)
    void nullsAreRejected(Policy policy) {
        BoundedCache<String, Integer> cache = cache(policy, 2, new RecordingListener<>());

        assertThrows(NullPointerException.class, () -> cache.get(null));
        assertThrows(NullPointerException.class, () -> cache.put(null, 1));
        assertThrows(NullPointerException.class, () -> cache.put("a", null));
        assertThrows(NullPointerException.class, () -> cache.remove(null));
    }

    /** Proves a bad bound, two bounds, no bound or a zero TTL fail fast with the documented exception. */
    @Test
    void badConfigurationFailsAtBuildTime() {
        assertThrows(IllegalArgumentException.class, () -> Cache.lru(0));       // LeetCode 460 allows 0; we don't
        assertThrows(IllegalArgumentException.class, () -> Cache.lfu(-1));
        assertThrows(IllegalStateException.class,
                () -> CacheBuilder.<String, Integer>newBuilder().maximumSize(2).maximumWeight(10, (k, v) -> v));
        assertThrows(IllegalStateException.class, () -> CacheBuilder.<String, Integer>newBuilder().build());
        assertThrows(IllegalArgumentException.class,
                () -> CacheBuilder.<String, Integer>newBuilder().expireAfterWrite(java.time.Duration.ZERO));
    }
}
