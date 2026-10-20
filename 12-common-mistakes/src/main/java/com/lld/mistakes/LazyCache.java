package com.lld.mistakes;

import java.util.function.Function;

/** Load-once cache guarded by a {@code ReentrantReadWriteLock}. */
public interface LazyCache<K, V> {

    V get(K key, Function<? super K, ? extends V> loader);
}
