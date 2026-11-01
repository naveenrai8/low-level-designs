package com.lld.mistakes;

import java.util.Optional;

/**
 * <b>Trade-off (M3), not a free win.</b> Split the cache into N independently locked LRUs. That gives less contention, but it is no longer an LRU.
 * <p>Each segment holds {@code capacity / N}, so keys that hash together evict each other while other segments sit empty. The evicted entry is the least recent <em>in its segment</em>, not globally. Say this out loud if you propose it.
 */
public final class SegmentedLruCache<K, V> {

    private final LruCache<K, V>[] segments;

    /**
     * Creates the segments. Throws {@code IllegalArgumentException} unless the capacity divides evenly into the segments.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})   // generic array creation
    public SegmentedLruCache(int capacity, int segmentCount) {
        if (segmentCount <= 0 || capacity % segmentCount != 0) {
            throw new IllegalArgumentException("capacity must divide evenly into segments");
        }
        segments = new LruCache[segmentCount];
        for (int i = 0; i < segmentCount; i++) {
            segments[i] = new LruCache<>(capacity / segmentCount);
        }
    }

    /**
     * Picks the segment for a key from its hash code.
     */
    private LruCache<K, V> segmentFor(K key) {
        return segments[Math.floorMod(key.hashCode(), segments.length)];
    }

    /**
     * Looks the key up in its own segment.
     */
    public Optional<V> get(K key) {
        return segmentFor(key).get(key);
    }

    /**
     * Stores the key in its own segment, which may evict from that segment even if others have room.
     */
    public void put(K key, V value) {
        segmentFor(key).put(key, value);
    }

    /**
     * Says whether the key is in its segment, without counting as a use.
     */
    public boolean containsKey(K key) {
        return segmentFor(key).containsKey(key);
    }

    /**
     * Adds up the sizes of all segments.
     */
    public int size() {
        int total = 0;
        for (LruCache<K, V> s : segments) {
            total += s.size();
        }
        return total;
    }
}
