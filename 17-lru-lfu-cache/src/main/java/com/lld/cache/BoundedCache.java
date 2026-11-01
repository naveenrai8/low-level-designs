package com.lld.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

/**
 * The one implementation of {@link Cache}: a {@code HashMap} for storage, an {@link EvictionPolicy} for "who goes next", and <b>one plain lock</b> around both.
 *
 * <ul>
 *   <li><b>Why one lock, not a read-write lock:</b> {@code get} reorders the policy (LRU moves the node, LFU bumps the frequency), so every {@code get} is a write. Topic 12 (M2) proves it.</li>
 *   <li><b>Evict before insert.</b> Inserting first and then asking LFU for a victim evicts the new key itself, because it has the lowest frequency (1).</li>
 *   <li><b>Listener outside the lock.</b> Removals are collected while locked and dispatched after {@code unlock()}, each in its own try/catch (Topic 12, M7). Under the lock a slow listener would stall every caller, and a listener that waits on another thread that touches the cache would deadlock.</li>
 *   <li><b>TTL is half-open and overflow-safe:</b> expired iff {@code now - expiresAt >= 0}. Expiry is lazy (noticed on access) plus an explicit O(n) {@link #cleanUp()}.</li>
 * </ul>
 */
final class BoundedCache<K, V> implements Cache<K, V> {

    /** One stored value with its weight and the tick (nanoseconds) at which it expires. */
    private record Entry<V>(V value, long weight, long expiresAt) {
    }

    private static final System.Logger LOG = System.getLogger(BoundedCache.class.getName());

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<K, Entry<V>> entries = new HashMap<>();
    private final EvictionPolicy<K> policy;
    private final long maxWeight;
    private final Weigher<? super K, ? super V> weigher;
    private final long ttlNanos;                     // 0 = never expires
    private final Ticker ticker;
    private final RemovalListener<? super K, ? super V> listener;
    private final BiConsumer<? super Removal<K, V>, ? super RuntimeException> onListenerError;

    // All guarded by lock.
    private long totalWeight;
    private long hits;
    private long misses;
    private long evictions;
    private long expirations;

    /**
     * Wires the cache together and checks the settings. Throws {@code IllegalArgumentException} for a bound below 1 or a negative TTL, and {@code NullPointerException} for any null part. A TTL of 0 means entries never expire.
     */
    BoundedCache(EvictionPolicy<K> policy, long maxWeight, Weigher<? super K, ? super V> weigher,
                 long ttlNanos, Ticker ticker, RemovalListener<? super K, ? super V> listener,
                 BiConsumer<? super Removal<K, V>, ? super RuntimeException> onListenerError) {
        if (maxWeight < 1) {
            throw new IllegalArgumentException("bound must be >= 1: " + maxWeight);
        }
        if (ttlNanos < 0) {
            throw new IllegalArgumentException("ttl must be >= 0: " + ttlNanos);
        }
        this.policy = Objects.requireNonNull(policy);
        this.maxWeight = maxWeight;
        this.weigher = Objects.requireNonNull(weigher);
        this.ttlNanos = ttlNanos;
        this.ticker = Objects.requireNonNull(ticker);
        this.listener = Objects.requireNonNull(listener);
        this.onListenerError = Objects.requireNonNull(onListenerError);
    }

    /**
     * Looks up a key. A hit counts as an access, so the policy reorders itself, which is why this takes the full lock. An expired entry is removed on the spot and counts as a miss. Listener calls happen after the lock is released.
     */
    @Override
    public Optional<V> get(K key) {
        Objects.requireNonNull(key, "key");
        List<Removal<K, V>> removed = new ArrayList<>();
        V value = null;
        lock.lock();
        try {
            Entry<V> entry = entries.get(key);
            if (entry != null && isExpired(entry, ticker.read())) {
                expire(key, entry, removed);
                entry = null;
            }
            if (entry == null) {
                misses++;
            } else {
                hits++;
                policy.onAccess(key);
                value = entry.value();
            }
        } finally {
            lock.unlock();
        }
        dispatch(removed);
        return Optional.ofNullable(value);
    }

    /**
     * Inserts or replaces a value, evicting others first if the new weight would not fit. A value heavier than the whole bound is refused (and any old value for that key is dropped too). Time is read inside the lock so per-key time never goes backwards. Listener calls happen after the lock is released.
     */
    @Override
    public void put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        long weight = weigher.weigh(key, value);      // caller code: run it outside the lock
        if (weight < 0) {
            throw new IllegalArgumentException("weigher returned " + weight + " for " + key);
        }
        List<Removal<K, V>> removed = new ArrayList<>();
        lock.lock();
        try {
            long now = ticker.read();                 // read inside the lock: per-key time never goes backwards
            Entry<V> old = entries.get(key);
            if (old != null && isExpired(old, now)) {
                expire(key, old, removed);
                old = null;
            }
            if (weight > maxWeight) {                 // could never fit: refuse it, don't flush the cache for it
                if (old != null) {
                    detach(key, old);
                    removed.add(new Removal<>(key, old.value(), RemovalCause.REPLACED));
                }
                evictions++;
                removed.add(new Removal<>(key, value, RemovalCause.SIZE));
            } else if (old != null) {
                totalWeight -= old.weight();          // old weight out first: `total + growth` could overflow
                policy.onAccess(key);
                removed.add(new Removal<>(key, old.value(), RemovalCause.REPLACED));
                evictWhile(weight, key, now, removed);   // growth evicts others, never the key just written
                entries.put(key, new Entry<>(value, weight, now + ttlNanos));
                totalWeight += weight;
            } else {
                evictWhile(weight, null, now, removed);  // evict BEFORE insert (see class doc)
                entries.put(key, new Entry<>(value, weight, now + ttlNanos));
                totalWeight += weight;
                policy.onInsert(key);
            }
        } finally {
            lock.unlock();
        }
        dispatch(removed);
    }

    /**
     * Removes a key and returns its value. If the entry had already expired, it is reported as EXPIRED and the result is empty, because logically it was already gone.
     */
    @Override
    public Optional<V> remove(K key) {
        Objects.requireNonNull(key, "key");
        List<Removal<K, V>> removed = new ArrayList<>();
        V value = null;
        lock.lock();
        try {
            Entry<V> entry = entries.get(key);
            if (entry != null && isExpired(entry, ticker.read())) {
                expire(key, entry, removed);          // logically already gone: report EXPIRED, return empty
            } else if (entry != null) {
                detach(key, entry);
                removed.add(new Removal<>(key, entry.value(), RemovalCause.EXPLICIT));
                value = entry.value();
            }
        } finally {
            lock.unlock();
        }
        dispatch(removed);
        return Optional.ofNullable(value);
    }

    /** Number of stored entries. May still count expired entries that nobody has touched yet. */
    @Override
    public int size() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }

    /** Walks every entry and removes the expired ones now. It is O(n), so call it on a schedule, not per request. Does nothing when no TTL is set. */
    @Override
    public int cleanUp() {
        List<Removal<K, V>> removed = new ArrayList<>();
        lock.lock();
        try {
            if (ttlNanos > 0) {
                long now = ticker.read();
                Iterator<Map.Entry<K, Entry<V>>> it = entries.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<K, Entry<V>> e = it.next();
                    if (isExpired(e.getValue(), now)) {
                        it.remove();
                        totalWeight -= e.getValue().weight();
                        policy.onRemove(e.getKey());
                        expirations++;
                        removed.add(new Removal<>(e.getKey(), e.getValue().value(), RemovalCause.EXPIRED));
                    }
                }
            }
        } finally {
            lock.unlock();
        }
        dispatch(removed);
        return removed.size();
    }

    /** Returns a consistent snapshot of the hit, miss, eviction and expiration counters. */
    @Override
    public CacheStats stats() {
        lock.lock();
        try {
            return new CacheStats(hits, misses, evictions, expirations);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Evicts until {@code incoming} fits. Written as {@code incoming > maxWeight - totalWeight} because {@code totalWeight + incoming} can overflow near {@code Long.MAX_VALUE}; the subtraction can't, since 0 &lt;= totalWeight &lt;= maxWeight. Terminates because incoming &lt;= maxWeight. A victim that has already expired is reported as {@code EXPIRED}.
     */
    private void evictWhile(long incoming, K exclude, long now, List<Removal<K, V>> removed) {
        while (incoming > maxWeight - totalWeight) {
            K victim = policy.victim(exclude);
            if (victim == null) {
                throw new IllegalStateException("over bound with nothing to evict: " + totalWeight);
            }
            Entry<V> entry = entries.get(victim);
            if (isExpired(entry, now)) {
                expire(victim, entry, removed);       // it left because its time was up, not for size
            } else {
                detach(victim, entry);
                evictions++;
                removed.add(new Removal<>(victim, entry.value(), RemovalCause.SIZE));
            }
        }
    }

    /** Removes an expired entry and records it as an EXPIRED removal (not a size eviction). */
    private void expire(K key, Entry<V> entry, List<Removal<K, V>> removed) {
        detach(key, entry);
        expirations++;
        removed.add(new Removal<>(key, entry.value(), RemovalCause.EXPIRED));
    }

    /** Takes an entry out of storage, the weight total and the policy, without recording any event. */
    private void detach(K key, Entry<V> entry) {
        entries.remove(key);
        totalWeight -= entry.weight();
        policy.onRemove(key);
    }

    /** True once {@code now} has reached the entry's expiry tick. Always false when no TTL is set. */
    private boolean isExpired(Entry<V> entry, long now) {
        return ttlNanos > 0 && now - entry.expiresAt() >= 0;   // subtraction, not `now >= expiresAt`: survives nanoTime overflow
    }

    /**
     * Sends each removal to the listener, one try/catch per event so one bad listener call cannot hide the others. A listener failure goes to the error handler; if that throws too, it is logged. Errors such as OutOfMemoryError still propagate.
     */
    private void dispatch(List<Removal<K, V>> removed) {
        for (Removal<K, V> r : removed) {
            try {
                listener.onRemoval(r.key(), r.value(), r.cause());
            } catch (RuntimeException e) {            // Errors (OOM etc.) still propagate
                try {
                    onListenerError.accept(r, e);
                } catch (RuntimeException handlerFailure) {   // caller code too: don't lose the other events
                    handlerFailure.addSuppressed(e);
                    LOG.log(System.Logger.Level.WARNING, "removal error handler threw for key " + r.key(), handlerFailure);
                }
            }
        }
    }

    // --- test hooks (package-private) ---------------------------------------------------------

    /** The stored keys, without touching recency or frequency. */
    Set<K> keySnapshot() {
        lock.lock();
        try {
            return new HashSet<>(entries.keySet());
        } finally {
            lock.unlock();
        }
    }

    /** Lets tests prove that the listener runs after the lock is released. */
    boolean isLockHeldByCurrentThread() {
        return lock.isHeldByCurrentThread();
    }

    /** Storage and policy hold the same keys, the policy's own structure is sound, and the weight accounting hasn't drifted. */
    void checkInvariants() {
        lock.lock();
        try {
            if (entries.size() != policy.size()) {
                throw new IllegalStateException("map " + entries.size() + " != policy " + policy.size());
            }
            for (K key : entries.keySet()) {         // equal sizes + containment = equal key sets
                if (!policy.contains(key)) {
                    throw new IllegalStateException("stored but not tracked: " + key);
                }
            }
            policy.checkInvariants();
            long sum = 0;
            for (Entry<V> e : entries.values()) {
                sum += e.weight();
            }
            if (sum != totalWeight) {
                throw new IllegalStateException("weight drift: sum " + sum + " != total " + totalWeight);
            }
            if (totalWeight > maxWeight) {
                throw new IllegalStateException("over bound: " + totalWeight + " > " + maxWeight);
            }
        } finally {
            lock.unlock();
        }
    }
}
