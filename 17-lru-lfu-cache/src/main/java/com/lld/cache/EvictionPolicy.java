package com.lld.cache;

/**
 * The Strategy seam: <em>which key goes next</em>, kept apart from storage and locking.
 *
 * <p>Every method is O(1). Implementations are <b>not thread-safe</b>: {@link BoundedCache} calls them only while holding its lock, so the concurrency is written once, in one place.
 *
 * <p>The cost of the seam: each policy keeps its own key&nbsp;&rarr;&nbsp;node index. A {@code get} hit does exactly two hash lookups (storage map + policy index); {@code put} and {@code remove} do a few more. Still O(1); the constant is roughly 2&times; (an estimate, not benchmarked). Caffeine avoids it by putting the policy links inside the storage entry.
 */
interface EvictionPolicy<K> {

    /** Start tracking a key that is not tracked yet. */
    void onInsert(K key);

    /** A tracked key was read or overwritten. */
    void onAccess(K key);

    /** Stop tracking a key (removed, expired, or evicted). */
    void onRemove(K key);

    /** The key to evict next, never {@code exclude}; {@code null} if there is none. {@code exclude} protects the entry being written when an update grows its weight. */
    K victim(K exclude);

    /** How many keys are tracked. */
    int size();

    /** Whether the key is tracked. For {@link BoundedCache#checkInvariants()}. */
    boolean contains(K key);

    /** Throws {@code IllegalStateException} if the policy's own structure is corrupt. O(n): tests only. The default checks nothing; each policy checks what its O(1) claims rest on. */
    default void checkInvariants() {
    }
}
