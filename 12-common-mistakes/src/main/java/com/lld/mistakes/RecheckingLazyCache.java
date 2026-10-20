package com.lld.mistakes;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * <b>Fix (M1).</b> Release the read lock, take the write lock, and <b>re-check</b>: the answer you
 * got under the read lock is stale the moment you release it. This is the pattern from the
 * {@code ReentrantReadWriteLock} Javadoc ({@code CachedData}). In practice
 * {@code ConcurrentHashMap.computeIfAbsent} does all of this in one call and is usually the
 * better answer. Reach for a RW lock only when the guarded state isn't a map.
 */
public final class RecheckingLazyCache<K, V> implements LazyCache<K, V> {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<K, V> map = new HashMap<>();
    private final Gap gap;

    public RecheckingLazyCache(Gap gap) {
        this.gap = gap;
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> loader) {
        lock.readLock().lock();
        try {
            V v = map.get(key);
            if (v != null) {
                return v;
            }
        } finally {
            lock.readLock().unlock();
        }
        gap.pause();                                   // the same gap, made harmless by the re-check
        lock.writeLock().lock();
        try {
            V v = map.get(key);                        // re-check: someone may have loaded it
            if (v == null) {
                v = loader.apply(key);
                map.put(key, v);
            }
            return v;
        } finally {
            lock.writeLock().unlock();
        }
    }
}
