package com.lld.cache;

import java.util.Optional;

/**
 * A bounded, thread-safe, in-memory cache.
 *
 * <p>Error contract:
 * <ul>
 *   <li>{@code null} keys and values throw {@code NullPointerException}. (A {@code null} value could not be told apart from a miss, and {@code Optional} can't carry it anyway.)</li>
 *   <li>A negative weight from the {@link Weigher} throws {@code IllegalArgumentException} from {@code put}.</li>
 *   <li>Configuration fails fast in {@link CacheBuilder}. A bound &lt; 1 or a non-positive TTL throws {@code IllegalArgumentException} from that setter. Setting both bounds throws {@code IllegalStateException} from the second one. Setting neither throws {@code IllegalStateException} from {@code build()}.</li>
 * </ul>
 * <p>Nothing here blocks except on the internal lock.
 */
public interface Cache<K, V> {

    /** The live value, if any. A hit is an <em>access</em>: LRU makes it most recent, LFU adds 1 to its count. */
    Optional<V> get(K key);

    /**
     * Inserts or replaces. Replacing is an access, resets the TTL, and reports the old value as {@link RemovalCause#REPLACED}. May evict other entries. Writing over an <em>expired</em> entry is a fresh insert: the old value is reported {@link RemovalCause#EXPIRED}, not {@code REPLACED}. An entry heavier than the whole bound is not stored and is reported as {@link RemovalCause#SIZE}. If the key already had a value, that old value is <b>dropped too</b> ({@code REPLACED}, then {@code SIZE}), so the key is absent afterwards.
     */
    void put(K key, V value);

    /** Removes and returns the live value, if any ({@link RemovalCause#EXPLICIT}). */
    Optional<V> remove(K key);

    /** Stored entries. May include expired entries that nothing has touched yet. */
    int size();

    /** Purges every expired entry now and returns how many. O(n): schedule it, don't call it per request. */
    int cleanUp();

    /** A snapshot of the hit, miss, eviction and expiration counters. */
    CacheStats stats();

    /** Shortcut for an LRU cache that holds at most {@code capacity} entries, with no TTL. */
    static <K, V> Cache<K, V> lru(long capacity) {
        return CacheBuilder.<K, V>newBuilder().policy(Policy.LRU).maximumSize(capacity).build();
    }

    /** Shortcut for an LFU cache that holds at most {@code capacity} entries, with no TTL. */
    static <K, V> Cache<K, V> lfu(long capacity) {
        return CacheBuilder.<K, V>newBuilder().policy(Policy.LFU).maximumSize(capacity).build();
    }
}
