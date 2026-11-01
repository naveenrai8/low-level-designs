package com.lld.cache;

/**
 * A consistent snapshot of the counters, taken under the cache lock.
 *
 * @param hits        {@code get} calls that found a live entry
 * @param misses      {@code get} calls that found nothing, or only an expired entry
 * @param evictions   entries removed (or refused) to respect the size/weight bound
 * @param expirations entries removed because their TTL passed
 */
public record CacheStats(long hits, long misses, long evictions, long expirations) {

    /** Total {@code get} calls: hits plus misses. */
    public long requests() {
        return hits + misses;
    }
}
