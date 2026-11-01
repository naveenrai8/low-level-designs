package com.lld.mistakes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <b>Fix (M2).</b> In an LRU cache {@code get} <em>moves</em> the entry to the front, so it is a write. A read lock around {@code get} is not just slow, it is wrong: two "readers" relink the same list at once.
 * <p>One plain lock is the correct baseline. Under measured contention the next step is a library (Caffeine buffers reads and replays them under the lock), not a hand-rolled StampedLock.
 */
public final class LruCache<K, V> {

    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<K, V> map;

    /**
     * Creates a cache that keeps at most {@code capacity} entries and evicts the least recently used one. Throws {@code IllegalArgumentException} if the capacity is not positive.
     */
    public LruCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.map = new LinkedHashMap<>(16, 0.75f, true) {          // accessOrder = true
            /**
             * Tells the map to drop its oldest entry once the size goes over the capacity.
             */
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > capacity;
            }
        };
    }

    /**
     * Returns the value and marks it as most recently used. Takes the plain lock, not a read lock, because the lookup relinks the list.
     */
    public Optional<V> get(K key) {
        lock.lock();                                   // not a read lock: get relinks the list
        try {
            return Optional.ofNullable(map.get(key));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stores the value and evicts the least recently used entry if the cache is over capacity.
     */
    public void put(K key, V value) {
        lock.lock();
        try {
            map.put(key, value);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Says whether the key is present. Does not count as a use, so it does not change the eviction order.
     */
    public boolean containsKey(K key) {                // containsKey does NOT touch access order
        lock.lock();
        try {
            return map.containsKey(key);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns how many entries are cached.
     */
    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }
}
