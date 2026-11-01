package com.lld.cache;

/** The cost of one entry against {@code maximumWeight} (e.g. bytes). Must return a value &gt;= 0. */
@FunctionalInterface
public interface Weigher<K, V> {
    /** Returns the cost of this entry. Called outside the lock; a negative result makes {@code put} throw. */
    long weigh(K key, V value);
}
