package com.lld.mistakes;

import java.util.function.Function;

/**
 * Load-once cache guarded by a {@code ReentrantReadWriteLock}.
 */
public interface LazyCache<K, V> {

    /**
     * Returns the cached value, calling the loader to create it on a miss. The loader should run once per key.
     */
    V get(K key, Function<? super K, ? extends V> loader);
}
