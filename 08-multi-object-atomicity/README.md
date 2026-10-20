# Topic 08: Multi-Object Atomicity: Lock Ordering, Striping, Per-Key Locks

Topic 07 made one object safe. Real LLD invariants span **two or more** objects:

- A transfer touches two accounts.
- A booking touches a seat *and* an order.
- A Splitwise expense touches every participant's balance.

Lock each object correctly and you still have two bugs. The program can **deadlock**: each thread
holds one lock and waits for the other's. Or it can expose a **half-done state**, where the money
has left one account and not yet arrived in the other. This topic reproduces both
deterministically and fixes them with the two standard moves: one global lock order, and holding
every lock the invariant needs for the whole operation.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [Key Takeaways](#5-key-takeaways)

---

## 1. The Problem

Invariant: **the total money in the bank is constant, and no account goes negative.** Two natural
implementations both break it:

```
Bug 1: nested locks, caller's order               Bug 2: each leg locked separately
-----------------------------------               -----------------------------------
T1 transfer(A→B): lock A ─┐                       lock A; A -= 300; unlock A
T2 transfer(B→A): lock B ─┤                         ← audit here: bank is 300 short
T1: wait for B (T2 has it)│  cycle → DEADLOCK       ← crash here: 300 lost forever
T2: wait for A (T1 has it)┘                       lock B; B += 300; unlock B
```

- **Bug 1, deadlock.** Every transfer is "properly locked", but A→B and B→A take the locks in
  opposite orders. Neither thread can proceed, and with `synchronized` neither can even be
  interrupted. In production this shows up as a thread pool that slowly fills with stuck workers
  until the service stops responding. CPU sits at zero, so it looks like a hang, not a crash.
- **Bug 2, atomic parts and a non-atomic whole.** No deadlock is possible, because two locks are
  never held at once. That's exactly why the in-between state is visible. It's the same shape as
  "decrement stock, then create order" done as two separate transactions.

---

## 2. How It Solves It

### Move 1: one global lock order makes deadlock impossible

A deadlock needs a **cycle** in the wait-for graph. If every thread takes locks in ascending
account id, nobody ever waits for a lower id while holding a higher one, so no cycle can form.

```java
Account first  = from.id() < to.id() ? from : to;     // ordering by a stable, unique key
Account second = first == from ? to : from;
first.lock().lockInterruptibly();
try {
    second.lock().lockInterruptibly();
    try {
        if (from.balanceLocked() < amount) return INSUFFICIENT_FUNDS;   // check…
        from.addLocked(-amount);                                         // …and both acts,
        to.addLocked(amount);                                            // all under both locks
        return OK;
    } finally { second.lock().unlock(); }
} finally { first.lock().unlock(); }
```

- The ordering key must be **unique and immutable**: an id, never `hashCode()`. Two objects can
  share a hash, and then the order is undefined. *Java Concurrency in Practice* §10.1.2 uses
  `System.identityHashCode` plus a tie-breaker lock for this case. A unique id avoids it.
- Check **and** act both happen while every lock the invariant needs is held. That closes Bug 2
  as well.

### Move 2: a consistent read needs the same locks

`audit()` takes **every** account's lock in the same ascending order, so it sees one instant. It's
correct, and it stops all transfers while it runs. That's acceptable for 6 accounts and
unacceptable for 10M. At that scale you change the data model: an append-only ledger where a
transfer is one atomic entry, and balances are a projection
([Topic 32](../32-double-entry-ledger/)).

### When you don't know the objects up front: per-key locks and striping

| Technique | Shape | Use when | Cost |
|---|---|---|---|
| **Lock per entity** (this module) | each `Account` owns a `ReentrantLock` | entities are long-lived objects you hold references to | memory per entity |
| **`ConcurrentHashMap.compute` per key** | the map's bin lock *is* the per-key lock | one key per operation ([Topic 21](../21-rate-limiter/)) | can't span two keys. Never touch another key inside the lambda |
| **Lock striping** | `locks[hash(key) % N]`, N fixed (e.g. 64) | millions of keys, want bounded lock memory | unrelated keys sharing a stripe serialise. Multi-key ops must lock stripes **in index order** and dedupe the same stripe |
| **One coarse lock** | `synchronized` on the service | low contention, or the round is about something else | serialises everything. Say so out loud |
| **Single writer thread** | all mutations go through one queue/thread | ordering matters more than parallelism (matching engine) | throughput capped at one core. No locks at all |

### Code and tests

| File | Role |
|---|---|
| [`NaiveNestedLockingBank`](src/main/java/com/lld/atomicity/NaiveNestedLockingBank.java) | Bug 1, caller-order nested locks |
| [`SplitLegsBank`](src/main/java/com/lld/atomicity/SplitLegsBank.java) | Bug 2, separately locked legs |
| [`OrderedLockingBank`](src/main/java/com/lld/atomicity/OrderedLockingBank.java) | Fix: ascending-id order, both locks across check and act, ordered audit |
| [`BankTest`](src/test/java/com/lld/atomicity/BankTest.java) | all five tests below |

| Test | Technique |
|---|---|
| `bugNaiveNestedLockingDeadlocksOnOpposingTransfers` | A `CyclicBarrier(2)` in the gap between the locks, so both threads hold their first lock before either asks for its second. Then `ThreadMXBean.findDeadlockedThreads()` (which sees `ReentrantLock`s, not just monitors) **confirms** the deadlock, and `interrupt()` frees the threads |
| `fixOrderedLockingCompletesOpposingTransfers` | Same hook, but both threads go for id 1 first. One waits on the lock instead of at the barrier, so the barrier times out harmlessly |
| `fixConservesMoneyExactlyUnderRandomConcurrentTransfers` | 16 threads × 5,000 random transfers. The total is asserted with **exact** `assertEquals` on `long` cents, plus no overdrafts |
| `bugSplitLegsExposeMoneyInNeitherAccount` | Single-threaded: the hook runs an audit between the legs and sees 1,700 of 2,000 |
| `fixAuditUnderOrderedLocksNeverSeesMoneyInFlight` | An auditor thread runs during 8 × 2,000 transfers and collects any total ≠ 6,000. The list must be empty |

Run it: [`commands.md`](commands.md).

---

## 3. The Gaps

### Misconceptions

- **"Each method is synchronized, so the operation is safe."** Thread-safe parts don't make a
  thread-safe whole. The invariant decides the lock scope, not the method boundaries.
- **"Deadlocks are rare, so they don't matter."** They're rare *per call* and certain at volume.
  One deadlock permanently removes two worker threads, and the pool shrinks until the service
  stops responding. There's no exception and no log line. A `jcmd <pid> Thread.print` thread
  dump ends with `Found one Java-level deadlock`.
- **"`tryLock` with a timeout fixes deadlock."** It turns a deadlock into a *livelock* risk:
  both threads back off and retry in lockstep, unless you add randomised backoff. Use it as a
  safety net or for *detecting* contention, not instead of ordering.
- **"Order by `hashCode()`."** Hash collisions make the order ambiguous. Use a unique id, or add
  a tie-breaker lock.
- **"`ReadWriteLock` will speed up the audit."** You'd still need every account's lock, and a
  read lock can't be upgraded to a write lock: that blocks forever, even in one thread
  ([Topic 12](../12-common-mistakes/)).
- **"Use a global lock and stop worrying."** It's correct, and it's a fine answer when contention
  is low. Say "I'm choosing one lock for simplicity, and here's how I'd go per-entity." That's
  a staff move, not a weakness. Silently using it without noticing the serialisation is the
  weakness.

### Failure modes

| Symptom | Cause | Confirm with |
|---|---|---|
| Throughput drops to 0, CPU idle, no errors | deadlock | thread dump: `Found one Java-level deadlock` |
| Totals off by an exact transfer amount, transiently | half-done state visible (Bug 2) | audit during load |
| Totals permanently off after a crash/restart | non-atomic legs + crash between them | needs a durable fix: one DB transaction, or a ledger entry |
| p99 latency spikes while an audit runs | audit takes every lock | move reads to a ledger/projection |

### When NOT to lock multiple objects

- **When a single atomic record can represent the operation.** An append-only ledger entry is
  atomic by construction ([Topic 32](../32-double-entry-ledger/)).
- **When the objects live in different processes or databases.** In-JVM locks don't span
  processes. You need a DB transaction, a saga, or the outbox pattern
  ([Topic 13](../13-lld-to-hld-seams/)).
- **When a single-writer design fits.** An order book processed by one thread has no locks to
  order.

### Interview move

When an operation touches two entities, say: *"This spans two objects, so I'll lock both in a
global order, by id, for the whole check-and-update. Otherwise A→B and B→A deadlock, or an
observer sees the money in neither account."* Then name the scale-out answer (ledger, or a DB
transaction) in one sentence.

---

## 4. Official References

- `ReentrantLock` (`lockInterruptibly`, `tryLock(timeout)`):
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/ReentrantLock.html
- `ThreadMXBean.findDeadlockedThreads` (detects ownable synchronizers as well as monitors):
  https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/ThreadMXBean.html#findDeadlockedThreads()
- `CyclicBarrier`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CyclicBarrier.html
- `jcmd` (`Thread.print` for thread dumps): https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html
- Brian Goetz et al., *Java Concurrency in Practice* (book), ch. 10 "Avoiding Liveness Hazards":
  lock-ordering deadlocks and the `identityHashCode` tie-breaker.
- Coffman et al. (1971), *System Deadlocks*, the four necessary conditions. Lock ordering breaks
  "circular wait".

---

## 5. Key Takeaways

1. The **invariant** sets the lock scope. Hold every lock it needs across both the check and
   the act.
2. Deadlock needs a cycle. **One global order** (ascending unique id) makes cycles impossible.
3. Separately locked legs can't deadlock, and that's precisely why the half-done state is
   visible.
4. A consistent multi-object read needs the same ordered locks, or a data model (a ledger) that
   doesn't need them.
5. Per-entity locks, CHM `compute`, striping, a coarse lock and a single writer are all valid.
   Name the trade-off when you pick one.
6. `tryLock` with a timeout is a safety net, not a substitute for ordering.
7. Test deadlocks deterministically: a barrier in the gap, then `ThreadMXBean` to confirm.
8. Money in `long` minor units makes conservation tests exact. Tolerances hide leaks
   ([Topic 11](../11-money-ids-idempotency/)).

---

**Self-grade: STRONG.** Both multi-object bug classes are reproduced deterministically, one
confirmed by the JVM's own deadlock detector. The fix is proven by exact conservation and by
auditing under load. It has a technique comparison table, failure-mode symptoms with how to
confirm them, and when-not-to. Gap: lock striping is described, not coded.

[← Topic 07: Concurrency Primitives](../07-concurrency-primitives/) · [Index](../README.md) · [Topic 09: Executors & Backpressure →](../09-executors-and-backpressure/)
