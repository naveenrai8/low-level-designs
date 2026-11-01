package com.lld.kvstore;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A per-thread handle on the store with nested transactions. Not thread-safe: give each thread its own session, and close it (try-with-resources) so an abandoned transaction does not pin old versions.
 *
 * <p>How it works: {@code begin()} pushes an empty overlay (a private map of pending changes) on a stack. Writes go into the top overlay. Reads look through the overlays from top to bottom, then fall back to the store as it was when the outermost transaction began. {@code rollback()} just throws the top overlay away, so values and TTLs of the outer level are untouched by construction. Inner {@code commit()} merges the top overlay into the one below. Only the outermost commit reaches the store, atomically.
 *
 * <p>Isolation: a transaction reads the committed values as of its begin (snapshot isolation) but judges expiry against the live clock, like a normal writer. With no transaction open, every call goes straight to the store and auto-commits.
 */
public final class Session implements KeyValueStore, AutoCloseable {

    private final InMemoryKvStore store;
    private final Clock clock;
    private final Deque<Map<String, InMemoryKvStore.Entry>> overlays = new ArrayDeque<>();
    private long startSeq;

    /** Creates a session. Called by {@link InMemoryKvStore#openSession()}. */
    Session(InMemoryKvStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Opens a (possibly nested) transaction. The outermost begin pins the committed state it will read from. */
    public void begin() {
        if (overlays.isEmpty()) {
            startSeq = store.registerSnapshot();
        }
        overlays.push(new HashMap<>());
    }

    /**
     * Commits the innermost transaction. If it is nested, its changes merge into the parent and stay invisible to everyone else. If it is the outermost, the changes are applied to the store atomically.
     *
     * @throws NoTransactionException if no transaction is open
     * @throws ConflictException if another session committed one of the same keys after our begin; this transaction is then gone
     */
    public void commit() {
        if (overlays.isEmpty()) {
            throw new NoTransactionException("commit");
        }
        Map<String, InMemoryKvStore.Entry> top = overlays.pop();
        if (!overlays.isEmpty()) {
            overlays.peek().putAll(top);
            return;
        }
        try {
            store.commitTransaction(startSeq, top);
        } finally {
            store.releaseSnapshot(startSeq);
        }
    }

    /**
     * Throws away the innermost transaction's changes. The parent's values and TTLs are exactly as they were before the inner {@code begin()}.
     *
     * @throws NoTransactionException if no transaction is open
     */
    public void rollback() {
        if (overlays.isEmpty()) {
            throw new NoTransactionException("rollback");
        }
        overlays.pop();
        if (overlays.isEmpty()) {
            store.releaseSnapshot(startSeq);
        }
    }

    /** Rolls back every open level, so an abandoned session cannot pin old versions forever. Safe to call with nothing open. */
    @Override
    public void close() {
        while (!overlays.isEmpty()) {
            rollback();
        }
    }

    /** Returns how many transactions are open (0 means auto-commit mode). */
    public int depth() {
        return overlays.size();
    }

    /** Stores a value (see {@link KeyValueStore}); inside a transaction it only writes to the top overlay. */
    @Override
    public void set(String key, String value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        if (overlays.isEmpty()) {
            store.set(key, value);
        } else {
            overlays.peek().put(key, new InMemoryKvStore.Entry(value, InMemoryKvStore.NO_EXPIRY));
        }
    }

    /** Stores a value (see {@link KeyValueStore}); inside a transaction it only writes to the top overlay. */
    @Override
    public void set(String key, String value, Duration ttl) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        if (overlays.isEmpty()) {
            store.set(key, value, ttl);
        } else {
            overlays.peek().put(key, new InMemoryKvStore.Entry(value, InMemoryKvStore.deadlineFor(ttl, clock.millis())));
        }
    }

    /** Returns the live value for the key, or empty (see {@link KeyValueStore}). */
    @Override
    public Optional<String> get(String key) {
        if (overlays.isEmpty()) {
            return store.get(key);
        }
        InMemoryKvStore.Entry e = effective(key);
        return e == null ? Optional.empty() : Optional.of(e.value());
    }

    /** Tells whether the key is live (see {@link KeyValueStore}). */
    @Override
    public boolean exists(String key) {
        return get(key).isPresent();
    }

    /** Removes the key and reports whether a live key was removed. */
    @Override
    public boolean delete(String key) {
        if (overlays.isEmpty()) {
            return store.delete(key);
        }
        boolean existed = effective(key) != null;
        overlays.peek().put(key, new InMemoryKvStore.Entry(null, InMemoryKvStore.NO_EXPIRY));
        return existed;
    }

    /** Gives a live key a new TTL from now; false if the key is not live. */
    @Override
    public boolean expire(String key, Duration ttl) {
        if (overlays.isEmpty()) {
            return store.expire(key, ttl);
        }
        long deadline = InMemoryKvStore.deadlineFor(ttl, clock.millis());
        InMemoryKvStore.Entry e = effective(key);
        if (e == null) {
            return false;
        }
        overlays.peek().put(key, new InMemoryKvStore.Entry(e.value(), deadline));
        return true;
    }

    /** Returns the time left, or empty when the key is missing, expired or has no TTL. */
    @Override
    public Optional<Duration> ttl(String key) {
        if (overlays.isEmpty()) {
            return store.ttl(key);
        }
        InMemoryKvStore.Entry e = effective(key);
        if (e == null || e.deadline() == InMemoryKvStore.NO_EXPIRY) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofMillis(e.deadline() - clock.millis()));
    }

    /** Counts live keys. */
    @Override
    public int count() {
        if (overlays.isEmpty()) {
            return store.count();
        }
        Set<String> candidates = store.allKeys();
        for (Map<String, InMemoryKvStore.Entry> overlay : overlays) {
            candidates.addAll(overlay.keySet());
        }
        int n = 0;
        for (String key : candidates) {
            if (effective(key) != null) {
                n++;
            }
        }
        return n;
    }

    /** Returns the live entry this transaction sees for a key (overlays first, then the pinned store state), or null if absent or expired. */
    private InMemoryKvStore.Entry effective(String key) {
        Objects.requireNonNull(key);
        long now = clock.millis();
        for (Map<String, InMemoryKvStore.Entry> overlay : overlays) {
            InMemoryKvStore.Entry e = overlay.get(key);
            if (e != null) {
                return e.liveAt(now) ? e : null;
            }
        }
        return store.readAt(key, startSeq, now);
    }
}
