# Topic 24: KV Store with TTL and Transactions

"Build an in-memory key-value store" sounds easy: a `HashMap` and three methods. The interviewer is waiting for what the simple answer gets wrong:

1. A key that should be gone is still returned, because nobody removed it, or it is removed late and memory grows.
2. A rollback restores the value but forgets the expiry, so a key lives forever or dies early.
3. Two writers commit over each other and one update is silently lost.

A staff answer names these first, then codes expiry, nested rollback and snapshot reads on one small design.

## Table of Contents

0. [Words used in this topic](#0-words-used-in-this-topic)
1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [45-Minute Interview Timeline](#5-45-minute-interview-timeline)
6. [Senior vs Staff Rubric](#6-senior-vs-staff-rubric)
7. [Key Takeaways](#7-key-takeaways)

---

## 0. Words used in this topic

| Word | Plain meaning |
|---|---|
| **TTL** | "Time to live". How long a key stays valid after it is set. |
| **Deadline** | The exact moment a key expires: set time plus TTL. |
| **Half-open** | The key is alive from the set time up to, but not including, the deadline. At exactly the deadline it is gone. |
| **Lazy expiry** | Check the deadline when someone reads the key, and delete it then if it is too old. |
| **Sweep** | A periodic cleaner that removes expired keys nobody reads. |
| **Transaction** | A group of changes that all take effect together, or not at all. |
| **Nested transaction** | A transaction opened inside another one. Rolling back the inner one must not undo the outer one. |
| **Overlay** | A private map of pending changes. Reads check it first, then the store. Dropping it is a rollback. |
| **Snapshot** | A frozen, read-only view of the store as it was at one moment. |
| **MVCC** | Multi-version concurrency control. Keep several versions of a key so readers never block writers. "Lite" here means one in-memory lock and a short version list. |
| **Tombstone** | A version that means "deleted". It is kept so older snapshots can still see the earlier value. |
| **WAL** | Write-ahead log. Write the change to a log first, then to memory, so a crash can be repaired by replaying the log. |
| **Torn record** | The last log record, half written because the process died mid-write. |

---

## 1. The Problem

Caches, session stores, feature flags and lock services all need "remember this value, and forget it after N seconds". Many also need "change several keys together, or none".

### What the interviewer is really probing

| Signal | What they want to see |
|---|---|
| Expiry correctness | Time is injected, the boundary is exact, expired keys are invisible before they are cleaned |
| Bounded cleanup | The sweep does limited work per call and never scans live keys |
| Rollback correctness | Values **and** TTLs come back, and a new key leaves no trace |
| Concurrency model | What a transaction is, when writes become visible, what happens on a clash |
| Honest limits | What snapshot reads cost and what a WAL needs to be real |

### v1 requirements and clarifying questions (assumed answers)

| Question | Assumed answer |
|---|---|
| Keys and values? | Strings. Null is rejected. |
| TTL? | Optional per key. Zero or negative TTL is an error. |
| Plain `set` on a key that has a TTL? | Clears the TTL (same as Redis `SET`). |
| Who sees an expired key? | Nobody: `get`, `exists`, `ttl`, `count` all hide it at once. |
| When does a TTL set inside a transaction start counting? | At the call, not at commit. A slow commit can install an already-dead key (Redis evaluates `SET EX` at `EXEC`). Pinned by `ttlInsideTransactionCountsFromTheCall`. |

**Out of scope:** other data types, eviction by memory, replication, a network protocol.

---

## 2. How It Solves It

### 2.0 Entities, invariants, interface

- **Entities:** `InMemoryKvStore` (committed state: a version list per key, plus a time-ordered expiry index), `Session` (per-thread transactions), `Snapshot` (frozen read view), `Wal` (commit log).
- **Invariants:** (1) a key is alive only for `set time <= now < deadline`; (2) the expiry index holds exactly one entry per live key that has a TTL; (3) only the outermost commit changes the store, in one atomic step; (4) a version is kept only while some snapshot or transaction may read it.

```java
public interface KeyValueStore {
    void set(String key, String value);                  // clears any old TTL
    void set(String key, String value, Duration ttl);    // ttl > 0 else IllegalArgumentException
    Optional<String> get(String key);                    // empty if missing OR expired
    boolean exists(String key);
    boolean delete(String key);                          // true only if a live key was removed
    boolean expire(String key, Duration ttl);            // false if not live
    Optional<Duration> ttl(String key);
    int count();                                         // live keys only
}
// Session adds begin()/commit()/rollback(): NoTransactionException when none is open, ConflictException on a lost race.
```

```
 Session --(reads/writes)--> overlay stack [O2, O1]        outermost commit
    |                                                  ------------------------> InMemoryKvStore --(append)--> Wal
    '--begin()--> pins commit number N                        (one lock, atomic)       |  version lists + expiry index
 Snapshot --(reads at N, clock frozen)---------------------------------------------'
```

### 2.1 Expiry: lazy plus a time-ordered sweep

Timeline (TTL 10 s, set at t=0, a fake clock the test moves by hand):

| Time | Step | Result |
|---|---|---|
| t=0 | `set("k","v",10s)` | deadline = 10,000 ms; key goes in the store and in the **expiry index** (sorted by deadline) |
| t=9.999 s | `get("k")` | alive, returns `v` |
| t=10 s | `get("k")` | exactly the deadline, so **gone**; the read removes it (lazy) |
| t=10 s | `count()` | does not count it, even if nobody read it and no sweep ran |
| t=11 s | `sweep(100)` | pops index entries with deadline <= now, at most 100, stops at the first future deadline |

Which sweep? A **time-ordered index** (`TreeSet`), not Redis-style random sampling. The sweep reads only the front, so cost is O(k log n) for k removed keys and it never touches live keys. Sampling needs no index but is only probabilistic, which Redis accepts for a cache. The index costs memory and O(log n) per TTL change. A popped entry is trusted only if the key's newest version is live with that exact deadline, so a stale entry cannot kill an extended key. While a snapshot pins an older version, the key is hidden at once but its memory is freed only when the snapshot closes.

### 2.2 Nested transactions: a stack of overlays

An **overlay** holds value and deadline together, so dropping it restores both. Timeline:

| Step | Call | Stack (top first) | Outside reader sees |
|---|---|---|---|
| 1 | `begin()` | [O1] | nothing |
| 2 | `set(k, "outer", 10s)` | [O1: k=outer,10s] | nothing |
| 3 | `begin()` | [O2, O1] | nothing |
| 4 | `delete(k)` (or change its TTL) | [O2: k=deleted, O1: k=outer,10s] | nothing |
| 5 | `rollback()` | [O1: k=outer,10s] | nothing (O2 just dropped) |
| 6 | `get(k)`, `ttl(k)` | `outer`, 10 s | nothing |
| 7 | `commit()` | [] | `k=outer` with its TTL, in one atomic step |

A key that did not exist before lives only in the dropped overlay, so no tombstone is left. Inner `commit()` copies the top overlay into the one below; only the outermost commit touches the store. Reads check overlays top to bottom, then the committed data as of the outermost `begin()`.

```java
try (Session s = store.openSession()) {   // close() rolls back anything left open
    s.begin();
    s.set("k", "outer", Duration.ofSeconds(10));
    s.begin();
    s.delete("k");
    s.rollback();                          // k is back with value AND its 10 s TTL
    s.commit();                            // now visible to everyone
}
```

### 2.3 Concurrency model

One store lock makes every call and commit atomic. A transaction belongs to one `Session` on one thread (not thread-safe on purpose). Writes stay private until the outermost commit. **First committer wins:** if any key we wrote got a user write after our `begin()`, commit throws `ConflictException` and the caller retries.

| Step | Session A | Session B | Store |
|---|---|---|---|
| 1 | `begin()` | `begin()` | k=0 |
| 2 | `set(k,"from-a")` | `set(k,"from-b")` | k=0 |
| 3 | | `commit()` ok | k=from-b |
| 4 | `commit()` throws `ConflictException` | | k=from-b |

Reads are **snapshot of committed data, live clock for expiry**: a transaction is a writer, so a key that lapses mid-transaction disappears from its view (`keyExpiresInsideTransaction`). A `Snapshot` is a frozen picture, so it freezes the clock too. That is why the two differ.

### 2.4 Snapshot reads (MVCC-lite)

Each key keeps a short list of **versions** stamped with a commit number. A snapshot remembers the commit number and the time it opened and reads "the newest version at or before my number".

| Step | Versions of `k` | Open snapshots | S1 (opened after step 1) reads |
|---|---|---|---|
| 1 `set(k,v1)` | [v1] | none | |
| 2 open S1 | [v1] | S1@1 | v1 |
| 3 `set(k,v2)`, `set(k,v3)` | [v1,v2,v3] | S1@1 | v1 |
| 4 `S1.close()` | [v3] | none | v1, v2 garbage-collected |

**Expiry rule:** a snapshot judges expiry at *snapshot time*. A key alive then stays visible after it expires and is swept; a key already expired then stays invisible. **GC cost:** collection runs only when the oldest pinned commit number moves, and visits only keys that hold more than one version (`GcCostTest`: 100 one-key transactions over 10k keys stay under 10k visits; a full scan visited 1,000,100). A snapshot or session that is never closed pins versions forever, so both are `AutoCloseable`.

### 2.5 Code and tests

| File | Role | Tests (48 total) |
|---|---|---|
| `KeyValueStore`, `InMemoryKvStore` | interface; version lists, expiry index, sweep, GC | `ExpiryTest` (8): exact-tick boundary, lazy expiry, bounded sweep, plain set clears TTL |
| `Session` | overlay stack, conflict check, `close()` | `TransactionTest` (14): rollback restores value and TTL, inner commit merges up, write skew allowed |
| `Snapshot` | frozen view, idempotent close | `SnapshotTest` (9), `GcCostTest` (2): isolation, expiry rule, GC, double close |
| `ConflictException`, `NoTransactionException` | the two defined errors | `ConcurrencyTest` (7): forced conflict, expiry vs user write, 8 x 200 = exactly 1600 |
| `Wal`, `Mutation` | commit log | `WalTest` (7): replay, torn tail, fail-stop, recovery handoff |
| (reference model) | | `DifferentialTest` (1): 20 seeds x 3000 ops incl. second session and snapshots vs a plain map plus stack of copies |

Time is a `java.time.Clock` ([Topic 10](../10-time-and-testability/)); tests use a `ManualClock` and never sleep. No `Ticker`: nothing measures elapsed time, and deadlines are wall-clock so a WAL can carry them across restarts.

---

## 3. The Gaps

**F1 nested transactions** and **F2 snapshot reads** are coded (2.2 to 2.4): an overlay stack and a version list with a pin registry.

### F3: WAL and crash recovery (coded, small)

A commit is **one log record** `[length][crc32][payload]` holding every change with absolute deadlines. The store appends, then applies. Replay stops at the first short or corrupt record. Once an append fails the log is **fail-stop**: later appends throw, because torn bytes may sit in the file and acknowledging more commits would lose them on replay (Postgres panics for the same reason). Recovery hands the new log the `validBytes` count: the new `Wal` must append to the **same file truncated to that length**, or history is lost at the next crash (`recoveryHandoffTruncatesToValidPrefix`). Expiry is not logged; replay re-derives it from deadlines.

| Production delta (not coded) | Why |
|---|---|
| `fsync` policy | `flush()` only hands bytes to the OS |
| Checkpoint plus log rotation | replay time grows with the log |
| Log sequence numbers, segments | to ship the log to replicas |
| Group commit | one fsync for many commits |

### Misconceptions and design choices

| Myth or choice | Truth |
|---|---|
| "Redis MULTI/EXEC can roll back" | It cannot. Errors found while queuing abort `EXEC` (since 2.6.5), but a command that fails at run time does not stop the others, and `DISCARD` only drops the queue. Here, writes sit in an overlay until commit. |
| "A sweep alone is enough" | Between sweeps an expired key is still stored. Lazy expiry makes it invisible at once. |
| "Rollback = restore the old value" | The TTL is state too. Restore the pair. |
| Design choice: expiry is not a conflict | A store-made expiry tombstone does not abort a writer; a later user write does (`userWriteAfterExpiryStillConflicts`). Redis `WATCH` does count expiry as a change [since 6.0.9](https://redis.io/docs/latest/develop/using-commands/transactions/). Our cost: a transaction can revive a lapsed lease if only readers saw the gap. |
| "Snapshot isolation is serializable" | Write skew passes (`writeSkewIsAllowed`). Fix with read-set validation. |

### Trade-offs and when not to

- **Index vs sampling:** exact and bounded, costs memory. Pick it when stale keys have a cost (locks, sessions).
- **One global lock:** simple, but hot. Shard by key hash if profiling says so, then multi-key commits need ordered locks ([Topic 08](../08-multi-object-atomicity/)).
- **Optimistic commit:** no blocking when clashes are rare; retries pile up on a hot key. Use locks if clashes are common.
- **Not for plain caching:** if you only need size-based eviction see [Topic 17](../17-lru-lfu-cache/), which overlaps on expiry. For durability and queries use a database.

**Production and distributed delta:** replicate the WAL, per-key versions across nodes, sharded maps, a rate-limited timer thread for active expiry, memory accounting. Real systems: Redis [expiry](../../pocs/Redis-poc/06-key-management/02-expiry-ttl-eviction/) and [MULTI/EXEC](../../pocs/Redis-poc/05-transactions/01-multi-exec/); Postgres [MVCC](../../pocs/Postgres-poc/10_transactions/) and [WAL](../../pocs/Postgres-poc/11_wal/).

---

## 4. Official References

- Redis, [EXPIRE](https://redis.io/docs/latest/commands/expire/): lazy expiry plus active sampling.
- Redis, [Transactions](https://redis.io/docs/latest/develop/using-commands/transactions/): MULTI/EXEC, no rollback, WATCH and expiry.
- Redis, [DISCARD](https://redis.io/docs/latest/commands/discard/).
- PostgreSQL, [MVCC introduction](https://www.postgresql.org/docs/current/mvcc-intro.html) and [WAL introduction](https://www.postgresql.org/docs/current/wal-intro.html).
- Kleppmann, *Designing Data-Intensive Applications*, 1st edition: ch. 3 (log-structured storage) and ch. 7 (snapshot isolation, write skew). In the 2nd edition these are ch. 4 and ch. 8.

---

## 5. 45-Minute Interview Timeline

| Minutes | What you do |
|---|---|
| 0-5 | Clarify types, TTL rules, plain set clears TTL. Write the interface and errors. |
| 5-15 | get/set/delete with TTL, injected clock, lazy expiry, half-open boundary. |
| 15-20 | The sweep: name the strategy and bound the work. |
| 20-32 | Nested transactions with an overlay stack; walk the rollback timeline with a TTL. |
| 32-40 | Concurrency model, first committer wins, or snapshots. |
| 40-45 | WAL design delta, tests (boundary, rollback TTL, differential), production gaps. |

---

## 6. Senior vs Staff Rubric

| Area | Senior | Staff |
|---|---|---|
| Expiry | Lazy check on read | Half-open boundary, bounded sweep with a named strategy, invisible before cleanup |
| Rollback | Copy the map | Overlay stack: values and TTLs restore by construction |
| Concurrency | "Add a lock" | Per-session transactions, atomic outermost commit, conflict policy and write skew |
| Snapshots | Copy everything | Versions plus a GC rule, a stated expiry rule, bounded GC cost |
| Durability | "Use a database" | Record per commit, CRC, torn tail, fail-stop, fsync and checkpoint trade-offs |

---

## 7. Key Takeaways

1. Inject the clock, make the deadline half-open, test at exactly the deadline.
2. Lazy expiry gives correctness; the sweep only returns memory, so bound it.
3. An overlay holds value and TTL together, so rollback is "drop it".
4. First committer wins is cheap but not serializable; close sessions and snapshots.
5. A WAL record per commit with a CRC, fail-stop on error, truncate to the valid prefix on recovery.

---

[← Topic 23: Delayed Job Scheduler](../23-delayed-job-scheduler/) · [Index](../README.md) · [Topic 25: Pub-Sub Message Queue](../25-pub-sub/)

**Self-grade: STRONG.** The remaining gap: one global lock with no sharding benchmark, GC cost bounded by a test but not benchmarked on large chains, and the WAL has no fsync or checkpoint (design delta only).
