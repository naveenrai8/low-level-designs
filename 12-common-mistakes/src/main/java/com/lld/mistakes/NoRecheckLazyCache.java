package com.lld.mistakes;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * <b>Bug (M1).</b> A read-write lock cannot be upgraded. Calling {@code writeLock().lock()} while holding the read lock blocks forever, even in a single thread.
 * <p>So this code does the "obvious" thing: it releases the read lock and then takes the write lock. That avoids the deadlock, but anything can happen in between, and this class acts on the stale "missing" answer without re-checking.
 * <p>Example: threads A and B both miss, both release the read lock, and both take the write lock one after the other. Each one loads the value, so it is loaded twice. In the original LRU code the same gap let another thread evict the node that was about to be moved.
 */
public final class NoRecheckLazyCache<K, V> implements LazyCache<K, V> {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<K, V> map = new HashMap<>();
    private final Gap gap;

    /**
     * Creates the broken cache. The gap sits after the read lock is released so a test can send two threads through it together.
     */
    public NoRecheckLazyCache(Gap gap) {
        this.gap = gap;
    }

    /**
     * Looks up under the read lock, and on a miss releases it, pauses in the gap, takes the write lock and loads without checking again. Two threads that both missed both load.
     */
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
