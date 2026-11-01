package com.lld.cache;

/**
 * Notified of every removal. Called <b>outside</b> the cache lock and on the thread that caused the removal, so it may call back into the cache, and a slow listener never blocks other callers. The flip side: events from two threads can arrive in either order.
 */
@FunctionalInterface
public interface RemovalListener<K, V> {
    /** Called once for each entry that left the cache, with the reason it left. */
    void onRemoval(K key, V value, RemovalCause cause);
}
