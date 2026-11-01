package com.lld.cache;

/** Which entry to evict when the cache is full. */
public enum Policy {
    /** Least recently used: evict the entry untouched for longest. */
    LRU,
    /** Least frequently used: evict the lowest access count; ties go to the least recently used. */
    LFU;

    /** Creates a fresh, empty policy object of this kind. */
    <K> EvictionPolicy<K> newPolicy() {
        return switch (this) {
            case LRU -> new LruPolicy<>();
            case LFU -> new LfuPolicy<>();
        };
    }
}
