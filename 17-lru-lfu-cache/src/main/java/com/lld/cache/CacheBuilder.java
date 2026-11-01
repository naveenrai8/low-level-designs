package com.lld.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Configures a {@link Cache}. A builder earns its place here: six optional settings, two of them mutually exclusive (Topic 05).
 *
 * <pre>{@code
 * Cache<String, byte[]> cache = CacheBuilder.<String, byte[]>newBuilder()
 *         .policy(Policy.LFU)
 *         .maximumWeight(64 << 20, (k, v) -> v.length)
 *         .expireAfterWrite(Duration.ofMinutes(5))
 *         .removalListener((k, v, cause) -> metrics.count(cause), (removal, e) -> log.warn(...))
 *         .build();
 * }</pre>
 */
public final class CacheBuilder<K, V> {

    private Policy policy = Policy.LRU;
    private long maxWeight = -1;
    private Weigher<? super K, ? super V> weigher;
    private long ttlNanos;
    private Ticker ticker = Ticker.system();
    private RemovalListener<? super K, ? super V> listener = (k, v, cause) -> { };
    private BiConsumer<? super Removal<K, V>, ? super RuntimeException> onListenerError = (r, e) -> { };

    /** Private so callers must go through {@link #newBuilder()}. */
    private CacheBuilder() {
    }

    /** Starts a new builder. Defaults: LRU, system ticker, no TTL, and a listener that does nothing. */
    public static <K, V> CacheBuilder<K, V> newBuilder() {
        return new CacheBuilder<>();
    }

    /** Chooses the eviction policy (LRU or LFU). The default is LRU. */
    public CacheBuilder<K, V> policy(Policy policy) {
        this.policy = Objects.requireNonNull(policy);
        return this;
    }

    /** At most {@code size} entries. Mutually exclusive with {@link #maximumWeight}. */
    public CacheBuilder<K, V> maximumSize(long size) {
        return bound(size, (k, v) -> 1);
    }

    /** Total {@code weigher} cost at most {@code weight}. Mutually exclusive with {@link #maximumSize}. */
    public CacheBuilder<K, V> maximumWeight(long weight, Weigher<? super K, ? super V> weigher) {
        return bound(weight, Objects.requireNonNull(weigher));
    }

    /** Entries expire {@code ttl} after their last write. Reads don't extend it. Throws {@code IllegalArgumentException} for a zero or negative TTL. */
    public CacheBuilder<K, V> expireAfterWrite(Duration ttl) {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive: " + ttl);
        }
        this.ttlNanos = ttl.toNanos();
        return this;
    }

    /** Replaces the time source. Tests pass a fake ticker so expiry needs no sleeping. */
    public CacheBuilder<K, V> ticker(Ticker ticker) {
        this.ticker = Objects.requireNonNull(ticker);
        return this;
    }

    /**
     * Registers a listener for every removal.
     *
     * @param listener called after the lock is released, once per removal
     * @param onError  receives any {@code RuntimeException} the listener throws, so a failure is reported instead of swallowed or rethrown into the caller of {@code put}
     */
    public CacheBuilder<K, V> removalListener(RemovalListener<? super K, ? super V> listener,
                                              BiConsumer<? super Removal<K, V>, ? super RuntimeException> onError) {
        this.listener = Objects.requireNonNull(listener);
        this.onListenerError = Objects.requireNonNull(onError);
        return this;
    }

    /** Builds the cache. Throws {@code IllegalStateException} if neither a size nor a weight bound was set. */
    public Cache<K, V> build() {
        return buildBounded();
    }

    /** Same as {@link #build()} but returns the concrete type so tests can reach the package-private hooks. */
    BoundedCache<K, V> buildBounded() {
        if (weigher == null) {
            throw new IllegalStateException("set maximumSize or maximumWeight");
        }
        return new BoundedCache<>(policy.newPolicy(), maxWeight, weigher, ttlNanos, ticker, listener, onListenerError);
    }

    /** Shared by both bound setters. Rejects a second bound and any value below 1. */
    private CacheBuilder<K, V> bound(long bound, Weigher<? super K, ? super V> weigher) {
        if (this.weigher != null) {
            throw new IllegalStateException("maximumSize and maximumWeight are mutually exclusive");
        }
        if (bound < 1) {
            throw new IllegalArgumentException("bound must be >= 1: " + bound);
        }
        this.maxWeight = bound;
        this.weigher = weigher;
        return this;
    }
}
