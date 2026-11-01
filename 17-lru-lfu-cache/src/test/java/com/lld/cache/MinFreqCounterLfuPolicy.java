package com.lld.cache;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * <b>The popular LeetCode 460 solution, kept as a foil.</b> A {@code minFreq} counter plus a map of frequency to an insertion-ordered key set. It's O(1) because LeetCode only ever evicts while inserting, and every insert resets {@code minFreq = 1}.
 *
 * <p>{@code onRemove} can empty the minimum bucket and nothing repairs {@code minFreq}: finding the next non-empty frequency would need a scan. {@link MinFreqCounterLfuTest} shows when that staleness is harmless (count bound) and when it breaks eviction (weight bound).
 */
final class MinFreqCounterLfuPolicy<K> implements EvictionPolicy<K> {

    private final Map<K, Long> counts = new HashMap<>();
    private final Map<Long, LinkedHashSet<K>> buckets = new HashMap<>();
    private long minFreq;

    /** Starts tracking the key at count 1 and resets minFreq to 1. */
    @Override
    public void onInsert(K key) {
        counts.put(key, 1L);
        buckets.computeIfAbsent(1L, f -> new LinkedHashSet<>()).add(key);
        minFreq = 1;
    }

    /** Moves the key up one count. Bumps minFreq only when it just emptied the minimum bucket. */
    @Override
    public void onAccess(K key) {
        long f = counts.get(key);
        LinkedHashSet<K> from = buckets.get(f);
        from.remove(key);
        if (f == minFreq && from.isEmpty()) {
            minFreq++;                                   // safe here: the key itself just moved to f + 1
        }
        counts.put(key, f + 1);
        buckets.computeIfAbsent(f + 1, x -> new LinkedHashSet<>()).add(key);
    }

    /** Forgets the key. Does not repair minFreq, which is the bug this class demonstrates. */
    @Override
    public void onRemove(K key) {
        long f = counts.remove(key);
        buckets.get(f).remove(key);                      // minFreq may now point at an empty bucket
    }

    /** Returns a key from the minFreq bucket only, so a stale minFreq means no victim is found. */
    @Override
    public K victim(K exclude) {
        for (K key : buckets.getOrDefault(minFreq, new LinkedHashSet<>())) {
            if (!key.equals(exclude)) {
                return key;
            }
        }
        return null;                                     // only looks at minFreq: "nothing to evict"
    }

    /** How many keys are tracked. */
    @Override
    public int size() {
        return counts.size();
    }

    /** Whether the key is tracked. */
    @Override
    public boolean contains(K key) {
        return counts.containsKey(key);
    }
}
