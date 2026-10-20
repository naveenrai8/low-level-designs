package com.lld.mistakes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <b>Fix (M2).</b> In an LRU cache {@code get} <em>moves</em> the entry, so it's a write. A read
 * lock around {@code get} isn't just slow, it's wrong: two "readers" relink the same list at
 * once. One plain lock is the correct baseline. Under measured contention the next step is a
 * library (Caffeine buffers reads and replays them under the lock), not a hand-rolled
 * StampedLock.
 */
public final class LruCache<K, V> {

    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<K, V> map;

    public LruCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.map = new LinkedHashMap<>(16, 0.75f, true) {          // accessOrder = true
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > capacity;
            }
        };
    }

    public Optional<V> get(K key) {
        lock.lock();                                   // not a read lock: get relinks the list
        try {
            return Optional.ofNullable(map.get(key));
        } finally {
            lock.unlock();
        }
    }

    public void put(K key, V value) {
        lock.lock();
        try {
            map.put(key, value);
        } finally {
            lock.unlock();
        }
    }

    public boolean containsKey(K key) {                // containsKey does NOT touch access order
        lock.lock();
        try {
            return map.containsKey(key);
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }
}
