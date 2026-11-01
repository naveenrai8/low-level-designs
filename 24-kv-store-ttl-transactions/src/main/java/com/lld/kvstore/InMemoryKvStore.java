package com.lld.kvstore;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntFunction;

/**
 * The shared committed state. Used directly, every call is its own tiny auto-committed transaction. For real transactions use {@link #openSession()}; for frozen reads use {@link #openSnapshot()}.
 *
 * <p>Storage: each key holds a short list of versions (oldest first). A write appends a version stamped with a commit number. A delete appends a "tombstone" (a version with no value) so older snapshots can still see the old value.
 *
 * <p>Expiry: lazy (checked on every read, and a stale key is removed when touched) plus a sweep. The sweep uses a time-ordered index (a sorted set of deadline+key), so {@link #sweep(int)} pops only expired entries and does at most {@code maxRemovals} per call. It never scans live keys.
 *
 * <p>Concurrency model: one lock guards the whole store, so every operation and every commit is atomic. A transaction belongs to one {@link Session} (one thread). Its writes sit in a private overlay and reach the store in one atomic step on the outermost commit, with first-committer-wins conflict detection.
 */
public final class InMemoryKvStore implements KeyValueStore {

    /** The deadline value that means "never expires". */
    static final long NO_EXPIRY = Long.MAX_VALUE;

    /**
     * A key's visible content: a value (null means deleted, used inside transaction overlays) and its expiry deadline in epoch milliseconds.
     *
     * @param value the value, or null for "deleted"
     * @param deadline when it expires, or {@link #NO_EXPIRY}
     */
    record Entry(String value, long deadline) {
        /** Tells whether this entry holds a value that has not expired at time {@code now} (the deadline itself is already expired). */
        boolean liveAt(long now) {
            return value != null && now < deadline;
        }
    }

    /**
     * One stored version of a key.
     *
     * @param seq the commit number that wrote it
     * @param value the value, or null for a tombstone
     * @param deadline when it expires, or {@link #NO_EXPIRY}
     * @param byExpiry true if the store itself wrote this tombstone because the key expired (it must not count as a user conflict)
     */
    private record Version(long seq, String value, long deadline, boolean byExpiry) {
    }

    /**
     * An entry of the time-ordered expiry index: sorted by deadline, then by key.
     *
     * @param deadline when the key expires
     * @param key the key
     */
    private record DeadlineKey(long deadline, String key) implements Comparable<DeadlineKey> {
        /** Orders by deadline first so the earliest expiry is always at the front. */
        @Override
        public int compareTo(DeadlineKey o) {
            int c = Long.compare(deadline, o.deadline);
            return c != 0 ? c : key.compareTo(o.key);
        }
    }

    private final Clock clock;
    private final Wal wal;
    private final Map<String, List<Version>> chains = new HashMap<>();
    private final TreeSet<DeadlineKey> expiryIndex = new TreeSet<>();
    private final TreeMap<Long, Integer> activeSnapshots = new TreeMap<>();
    private long commitSeq;
    private long gcVisits;
    private final Set<String> multiVersion = new HashSet<>();

    /** Creates a store with no write-ahead log. */
    public InMemoryKvStore(Clock clock) {
        this(clock, null);
    }

    /** Creates a store that writes every commit to the given log before applying it. Pass null for no log. */
    public InMemoryKvStore(Clock clock, Wal wal) {
        this.clock = Objects.requireNonNull(clock);
        this.wal = wal;
    }

    /**
     * Rebuilds a store from a log after a crash. Torn trailing records are ignored.
     *
     * <p>Handoff rule: the function receives {@link Wal.Replay#validBytes()} and must return a {@link Wal} that appends to the SAME log file after truncating it to that many bytes (or to a new file that already holds exactly those bytes). A fresh empty log would lose all history on the next crash. Return null for no log.
     */
    public static InMemoryKvStore recover(Clock clock, byte[] log, IntFunction<Wal> newWalFor) {
        Wal.Replay replay = Wal.replay(log);
        InMemoryKvStore store = new InMemoryKvStore(clock, newWalFor.apply(replay.validBytes()));
        for (List<Mutation> batch : replay.batches()) {
            store.install(batch);
        }
        return store;
    }

    /** Opens a session: a per-thread handle that supports begin/commit/rollback. */
    public Session openSession() {
        return new Session(this, clock);
    }

    /** Opens a frozen read-only view as of now. Close it when done. */
    public synchronized Snapshot openSnapshot() {
        activeSnapshots.merge(commitSeq, 1, Integer::sum);
        return new Snapshot(this, commitSeq, clock.millis());
    }

    /** Stores the value with no expiry, as one auto-committed write. Clears any old TTL. */
    @Override
    public synchronized void set(String key, String value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        applyBatch(List.of(new Mutation(key, value, NO_EXPIRY)));
    }

    /** Stores the value with a TTL counted from now, as one auto-committed write. */
    @Override
    public synchronized void set(String key, String value, Duration ttl) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        applyBatch(List.of(new Mutation(key, value, deadlineFor(ttl, clock.millis()))));
    }

    /** Returns the live value for the key, or empty (see {@link KeyValueStore}). */
    @Override
    public synchronized Optional<String> get(String key) {
        Objects.requireNonNull(key);
        Version v = latest(key);
        if (v == null || v.value() == null) {
            return Optional.empty();
        }
        if (clock.millis() >= v.deadline()) {
            expireKey(key);
            return Optional.empty();
        }
        return Optional.of(v.value());
    }

    /** Tells whether the key is live (see {@link KeyValueStore}). */
    @Override
    public boolean exists(String key) {
        return get(key).isPresent();
    }

    /** Removes the key and reports whether a live key was removed. */
    @Override
    public synchronized boolean delete(String key) {
        if (get(key).isEmpty()) {
            return false;
        }
        applyBatch(List.of(new Mutation(key, null, NO_EXPIRY)));
        return true;
    }

    /** Gives a live key a new TTL from now; false if the key is not live. */
    @Override
    public synchronized boolean expire(String key, Duration ttl) {
        long deadline = deadlineFor(ttl, clock.millis());
        Optional<String> value = get(key);
        if (value.isEmpty()) {
            return false;
        }
        applyBatch(List.of(new Mutation(key, value.get(), deadline)));
        return true;
    }

    /** Returns the time left, or empty when the key is missing, expired or has no TTL. */
    @Override
    public synchronized Optional<Duration> ttl(String key) {
        Objects.requireNonNull(key);
        Version v = latest(key);
        long now = clock.millis();
        if (v == null || v.value() == null || now >= v.deadline() || v.deadline() == NO_EXPIRY) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofMillis(v.deadline() - now));
    }

    /** Counts live keys. */
    @Override
    public synchronized int count() {
        long now = clock.millis();
        int n = 0;
        for (List<Version> chain : chains.values()) {
            Version v = chain.get(chain.size() - 1);
            if (v.value() != null && now < v.deadline()) {
                n++;
            }
        }
        return n;
    }

    /**
     * Removes expired keys, at most {@code maxRemovals} per call, and returns how many it expired.
     *
     * <p>It reads the front of the time-ordered index and stops at the first deadline that is still in the future, so it never touches live keys and one call is bounded work. Call it from a periodic task.
     *
     * <p>A key is expired only if its newest version is live with exactly the indexed deadline, so a stale index entry can never kill a key. The key disappears at once, but while a snapshot or transaction still pins an older version, that version stays in memory until it is released.
     */
    public synchronized int sweep(int maxRemovals) {
        long now = clock.millis();
        int removed = 0;
        while (removed < maxRemovals && !expiryIndex.isEmpty() && expiryIndex.first().deadline() <= now) {
            DeadlineKey due = expiryIndex.pollFirst();
            Version v = latest(due.key());
            if (v != null && v.value() != null && v.deadline() == due.deadline()) {
                expireKey(due.key());
                removed++;
            }
        }
        return removed;
    }

    /** Number of keys that still occupy memory, including expired ones not yet removed. For tests and metrics. */
    public synchronized int physicalSize() {
        return chains.size();
    }

    /** Total number of stored versions across all keys. Old versions only exist while a snapshot or transaction may need them. */
    public synchronized int versionCount() {
        int n = 0;
        for (List<Version> chain : chains.values()) {
            n += chain.size();
        }
        return n;
    }

    /** Counts how many keys the garbage collector has looked at so far. For tests that bound GC cost. */
    synchronized long gcVisits() {
        return gcVisits;
    }

    /** Converts a TTL into an absolute deadline from {@code now}, rejecting zero and negative TTLs. Saturates instead of overflowing. */
    static long deadlineFor(Duration ttl, long now) {
        long ms = ttl.toMillis();
        if (ms <= 0) {
            throw new IllegalArgumentException("ttl must be at least 1 ms: " + ttl);
        }
        return ms > NO_EXPIRY - 1 - now ? NO_EXPIRY - 1 : now + ms;
    }

    /** Returns the newest version of the key, or null if the key has no versions. */
    private Version latest(String key) {
        List<Version> chain = chains.get(key);
        return chain == null ? null : chain.get(chain.size() - 1);
    }

    /** Reads a key as of commit number {@code seq}, judging expiry at {@code nowMs}. Returns null if invisible. Used by snapshots and transactions. */
    synchronized Entry readAt(String key, long seq, long nowMs) {
        List<Version> chain = chains.get(key);
        if (chain == null) {
            return null;
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            Version v = chain.get(i);
            if (v.seq() <= seq) {
                Entry e = new Entry(v.value(), v.deadline());
                return e.liveAt(nowMs) ? e : null;
            }
        }
        return null;
    }

    /** Counts keys visible at commit number {@code seq} with expiry judged at {@code nowMs}. */
    synchronized int countAt(long seq, long nowMs) {
        int n = 0;
        for (String key : chains.keySet()) {
            if (readAt(key, seq, nowMs) != null) {
                n++;
            }
        }
        return n;
    }

    /** Returns a copy of every key that has any stored version, live or not. */
    synchronized Set<String> allKeys() {
        return new HashSet<>(chains.keySet());
    }

    /** Pins the current commit number so its versions are kept, and returns it. Used by transactions at begin. */
    synchronized long registerSnapshot() {
        activeSnapshots.merge(commitSeq, 1, Integer::sum);
        return commitSeq;
    }

    /** Returns the oldest pinned commit number, or Long.MAX_VALUE when nothing is pinned. */
    private long minPinned() {
        return activeSnapshots.isEmpty() ? Long.MAX_VALUE : activeSnapshots.firstKey();
    }

    /**
     * Un-pins a commit number. Garbage is only collectable when the oldest pin moved, and then only keys that hold more than one version (or a leading tombstone) can have any, so only those are visited.
     */
    synchronized void releaseSnapshot(long seq) {
        long before = minPinned();
        activeSnapshots.merge(seq, -1, Integer::sum);
        if (activeSnapshots.get(seq) <= 0) {
            activeSnapshots.remove(seq);
        }
        if (minPinned() != before) {
            for (String key : new ArrayList<>(multiVersion)) {
                gc(key);
            }
        }
    }

    /**
     * Applies an outermost transaction. Fails with {@link ConflictException} if any written key got a user write after {@code startSeq} (first committer wins). Otherwise logs and installs all writes as one atomic commit.
     */
    synchronized void commitTransaction(long startSeq, Map<String, Entry> writes) {
        for (String key : writes.keySet()) {
            List<Version> chain = chains.get(key);
            if (chain == null) {
                continue;
            }
            for (Version v : chain) {
                if (v.seq() > startSeq && !v.byExpiry()) {
                    throw new ConflictException(key);
                }
            }
        }
        if (writes.isEmpty()) {
            return;
        }
        List<Mutation> batch = new ArrayList<>();
        writes.forEach((k, e) -> batch.add(new Mutation(k, e.value(), e.value() == null ? NO_EXPIRY : e.deadline())));
        applyBatch(batch);
    }

    /** Writes the batch to the log first (write-ahead), then installs it in memory. */
    private void applyBatch(List<Mutation> batch) {
        if (wal != null) {
            wal.append(batch);
        }
        install(batch);
    }

    /** Installs a batch as one commit: all its versions share one commit number. */
    private synchronized void install(List<Mutation> batch) {
        long seq = ++commitSeq;
        for (Mutation m : batch) {
            appendVersion(m.key(), new Version(seq, m.value(), m.deadlineMs(), false));
        }
    }

    /** Removes an expired key by writing an expiry tombstone. Not logged: replay re-derives it from the deadline. */
    private void expireKey(String key) {
        appendVersion(key, new Version(++commitSeq, null, NO_EXPIRY, true));
    }

    /** Adds a version to a key, keeps the expiry index pointing at the newest version only, then garbage-collects the key. */
    private void appendVersion(String key, Version nv) {
        List<Version> chain = chains.computeIfAbsent(key, k -> new ArrayList<>());
        if (!chain.isEmpty()) {
            Version old = chain.get(chain.size() - 1);
            if (old.value() != null && old.deadline() != NO_EXPIRY) {
                expiryIndex.remove(new DeadlineKey(old.deadline(), key));
            }
        }
        chain.add(nv);
        if (nv.value() != null && nv.deadline() != NO_EXPIRY) {
            expiryIndex.add(new DeadlineKey(nv.deadline(), key));
        }
        gc(key);
    }

    /**
     * Drops versions nobody can read any more. The oldest open snapshot needs the newest version at or before its commit number; anything older is garbage. A leading tombstone is also garbage, because "no version" reads the same.
     */
    private void gc(String key) {
        gcVisits++;
        List<Version> chain = chains.get(key);
        if (chain == null) {
            multiVersion.remove(key);
            return;
        }
        long min = minPinned();
        int keep = -1;
        for (int i = chain.size() - 1; i >= 0; i--) {
            if (chain.get(i).seq() <= min) {
                keep = i;
                break;
            }
        }
        if (keep > 0) {
            chain.subList(0, keep).clear();
        }
        while (!chain.isEmpty() && chain.get(0).value() == null && chain.get(0).seq() <= min) {
            chain.remove(0);
        }
        if (chain.isEmpty()) {
            chains.remove(key);
        }
        if (chain.size() > 1 || (!chain.isEmpty() && chain.get(0).value() == null)) {
            multiVersion.add(key);
        } else {
            multiVersion.remove(key);
        }
    }
}
