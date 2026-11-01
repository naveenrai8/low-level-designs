package com.lld.kvstore;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A frozen, read-only view of the store (MVCC-lite).
 *
 * <p>Rule: the snapshot sees the committed values as of the moment it was opened, and judges expiry at that same moment. So a key that was alive then stays visible here even if it expires or is swept later, and a key that was already expired then stays invisible.
 *
 * <p>While a snapshot is open, the store keeps the old versions it may need. Always close it (try-with-resources) so those versions can be garbage-collected.
 */
public final class Snapshot implements AutoCloseable {

    private final InMemoryKvStore store;
    private final long seq;
    private final long timeMs;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Creates a snapshot pinned at a commit sequence number and a wall-clock time. Called by the store only. */
    Snapshot(InMemoryKvStore store, long seq, long timeMs) {
        this.store = store;
        this.seq = seq;
        this.timeMs = timeMs;
    }

    /** Returns the value as it was when the snapshot opened, or empty. Throws if the snapshot is closed. */
    public Optional<String> get(String key) {
        ensureOpen();
        InMemoryKvStore.Entry e = store.readAt(key, seq, timeMs);
        return e == null ? Optional.empty() : Optional.of(e.value());
    }

    /** Tells whether the key was alive when the snapshot opened. */
    public boolean exists(String key) {
        return get(key).isPresent();
    }

    /** Counts the keys that were alive when the snapshot opened. */
    public int count() {
        ensureOpen();
        return store.countAt(seq, timeMs);
    }

    /** Releases the snapshot so the store may drop old versions. Safe to call twice and from any thread: only the first call releases the pin. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            store.releaseSnapshot(seq);
        }
    }

    /** Throws if the snapshot was already closed, because its versions may be gone. */
    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("snapshot is closed");
        }
    }
}
