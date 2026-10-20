package com.lld.mistakes;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * <b>Bug (M1).</b> A read-write lock can't be upgraded, and calling {@code writeLock().lock()}
 * while holding the read lock blocks forever, even in a single thread. So this code does the
 * "obvious" thing: it releases the read lock and then takes the write lock. That avoids the
 * deadlock, but anything can happen in between, and this class acts on the stale "missing"
 * without re-checking. Two threads that both missed both load. In the original LRU code the
 * same gap let another thread evict the node that was about to be moved.
 */
public final class NoRecheckLazyCache<K, V> implements LazyCache<K, V> {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<K, V> map = new HashMap<>();
    private final Gap gap;

    public NoRecheckLazyCache(Gap gap) {
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
        gap.pause();                                   // no lock held: the world can change here
        lock.writeLock().lock();
        try {
            V v = loader.apply(key);                   // BUG: no re-check under the write lock
            map.put(key, v);
            return v;
        } finally {
            lock.writeLock().unlock();
        }
    }
}
