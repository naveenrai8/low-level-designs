# Topic 13: From LLD to HLD — Where the Seams Go

Every LLD round ends with some version of "now it runs on 40 machines". LLD means low-level design: the classes and code of one program. HLD means high-level design: many machines working together. The program you just coded starts to lie at that moment:

- the `ConcurrentHashMap` is now 40 different maps
- the `ReentrantLock` protects nothing across JVMs (separate running Java programs)
- `System.nanoTime()` from two machines can't be compared
- a method call used to return or throw, and can now also *time out with an unknown outcome*

You are not expected to build the distributed version in a coding round. The staff skill is to have **put the seams in the right places**. A *seam* is an interface where one piece can be swapped for another without touching the rest. Then you say, in two sentences, what changes behind each seam.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [Key Takeaways](#5-key-takeaways)

---

## 1. The Problem

A single-process design leans on guarantees that only hold inside one JVM. Each row below is one such guarantee and how it breaks:

| Guarantee you used | Why it held | What breaks with many processes |
|---|---|---|
| One copy of the data | one heap | K copies. The rate limiter lets through **K × limit** ([Topic 21](../21-rate-limiter/)) |
| Locks keep others out | one memory, one scheduler | a JVM lock means nothing to the other 39 JVMs |
| A distributed lock keeps others out | the lock has a lease (a time limit) | the holder can pause (GC, network) past the lease and still write |
| Time only moves forward | one `nanoTime` start point | each JVM has its own start point, and wall clocks drift and jump, so sorting by timestamp is unsafe |
| A call returns or throws | same address space | a third result appears: **timed out, outcome unknown**. Then come retries, then duplicates |
| A listener runs once | in-memory observer | brokers deliver **at least once**, and maybe out of order |
| The cache is the truth | it is the only copy | N caches plus a DB means stale data and a thundering herd (many callers hit the DB at once) |
| `AtomicLong` IDs are unique | one counter | each instance counts `1, 2, 3…`, so IDs collide |
| One lock covers two objects | one lock covers both | two services or DBs need a distributed transaction, a saga or an outbox |

The interviewer is checking whether you know which of these your design depends on. A design that hard-codes them (`new HashMap<>()` inside the domain, `System.currentTimeMillis()` everywhere) can't grow without a rewrite.

---

## 2. How It Solves It

### Put each in-process assumption behind a port

A *port* is a small interface the domain code talks to. The domain never knows what is behind it.

```java
public interface Clock { long nowNanos(); }                    // Ticker in Topic 21; java.time.Clock for wall time
public interface OrderRepository {
    Optional<Order> find(OrderId id);
    void save(Order order, long expectedVersion);              // optimistic concurrency: the version travels with the write
}
public interface EventPublisher { void publish(DomainEvent e); }  // in-memory list today; outbox + broker tomorrow
public interface IdGenerator { OrderId next(); }                  // UUIDv7 today; nothing to coordinate
```

The domain (aggregates, state machines, invariants) depends only on these ports. The LLD round ships in-memory versions. The HLD answer swaps them out. Four rules make a seam survive the swap:

1. **The signature must admit distributed failures.** `save(order, expectedVersion)` can say "someone else wrote first" (a conflict). An in-memory map never needs that, but a database does. A `void save(order)` built for a `HashMap` can't say it, and that gap is the leak.
2. **No in-process objects cross the seam.** Pass plain values (IDs, records). Never pass a lock, a live mutable entity shared by reference, or a callback the other side must call.
3. **Time comes in, it isn't fetched.** Domain methods take `now` (or a `Clock`). That makes them testable (Topic 10), and it lets the distributed version use server time.
4. **Every change carries an idempotency handle.** That is a key, a version or a sequence number. *Idempotent* means doing it twice has the same effect as doing it once, so at-least-once delivery is harmless (Topic 11).

### What changes behind each seam

| LLD component | In-process (what you code) | At scale (what you *say*) | Deep dive |
|---|---|---|---|
| Per-key state (rate limiter, counters) | `ConcurrentHashMap.compute` | one central store with an atomic script (Redis + Lua), or give each key to one owner node by consistent hashing | [SD 64](../../pocs/SystemDesign-poc/64-rate-limiting-and-admission-control/), [SD 41](../../pocs/SystemDesign-poc/41-partitioning-schemes/), [Topic 29](../29-consistent-hashing/) |
| Mutual exclusion | `ReentrantLock`, `synchronized` | prefer **conditional writes** (version check, `UPDATE … WHERE version = ?`). If you need a lock: a lease from a consensus-backed service **plus fencing tokens** that the resource checks | [SD 49](../../pocs/SystemDesign-poc/49-locks-and-coordination-services/), [SD 09](../../pocs/SystemDesign-poc/09-quorums-split-brain-and-fencing/) |
| Time | injected `Ticker` / `Clock` | server time at the single writer, logical or hybrid clocks for order, never compare `nanoTime` across hosts | [SD 08](../../pocs/SystemDesign-poc/08-time-clocks-and-ordering/) |
| Method calls | direct call | timeout, retry with backoff, idempotency key, circuit breaker | [SD 10](../../pocs/SystemDesign-poc/10-idempotency-and-exactly-once/), [Topic 27](../27-circuit-breaker-and-retry/) |
| Observer / event bus | in-memory listener list | a broker, at-least-once, order per key via partitions, idempotent consumers, an outbox for "DB write + publish" | [SD 50](../../pocs/SystemDesign-poc/50-messaging-semantics/), [SD 52](../../pocs/SystemDesign-poc/52-reliability-in-messaging/) |
| Cache | `LinkedHashMap` LRU (Topic 17) | a shared cache tier, an invalidation plan, stampede protection, TTL as a safety net | [SD 25](../../pocs/SystemDesign-poc/25-caching-strategies/), [SD 27](../../pocs/SystemDesign-poc/27-invalidation-and-consistency/) |
| ID generation | `AtomicLong` or UUIDv7 | UUIDv7 (no coordination) or Snowflake (node-ID assignment, clock-backwards guard) | [SD 87](../../pocs/SystemDesign-poc/87-distributed-id-generation/), [Topic 28](../28-id-generator/) |
| Multi-object atomicity | one lock, lock ordering (Topic 08) | one DB transaction if one DB, otherwise a saga or TCC with compensations (undo steps) | [SD 45](../../pocs/SystemDesign-poc/45-distributed-transactions/), [SD 46](../../pocs/SystemDesign-poc/46-sagas-tcc-reliability/) |

### Why "just add a distributed lock" is the wrong reflex

A lease-based lock has a time limit. The holder can stall past it, for example in a stop-the-world GC pause, a paused VM or a slow network. Then it wakes up and writes, even though a second holder already has the lock.

Worked example: client A gets the lock with fencing token 33 and then stalls in a long GC pause. The lease expires, and client B gets token 34 and writes. A wakes up and writes with token 33. The storage rejects it because it has already seen 34.

Kleppmann's fix is the **fencing token**. It is a number that only goes up, issued with each lease. The storage checks it on every write and rejects any older token. Without that check, the lock only saves duplicate work. It does not guarantee correctness. Prefer designs where the storage's own conditional write is the guard.

### How to spend interview time on this

```
coding phase (minutes 10-35)            follow-up phase (minutes 35-45)
──────────────────────────────          ─────────────────────────────────────────────
domain + invariants   (code)            "At 40 instances, three things change:"
ports with in-memory impls (code)        1. state: <seam> becomes <X> because <Y>
                                         2. concurrency: lock → conditional write / fencing
                                         3. failure: timeouts → idempotency key on <op>
                                        Draw the swap on the class diagram; don't code it.
```

Say one sentence per seam, then go back to the problem. Building a Redis client in an LLD round burns the time you need for tests.

---

## 3. The Gaps

### Common misconceptions

| "I thought…" | Actually |
|---|---|
| "An interface makes it swappable" | Only if the signature already allows conflicts, timeouts and duplicates. A `HashMap`-shaped interface leaks |
| "A Redis lock (`SET NX PX`) makes it safe" | Only with fencing at the resource. Otherwise a paused holder can still write |
| "Timestamps can order events" | Clocks on different hosts disagree, so events get reordered. Use one writer's sequence number, or partition order |
| "The broker gives exactly-once" | End to end you get at-least-once delivery plus idempotent processing. Build consumers for duplicates |
| "Retries make it reliable" | Retries without idempotency make duplicates. Retries without backoff and limits make a retry storm |
| "Sticky sessions keep state local, so we're done" | A rebalance, deploy or node loss moves the keys. State must be rebuildable or stored outside |

### Over-correcting is also a failure

- **A port for everything** (`StringFormatter`, `ListFactory`) is ceremony that hides the domain. Add a seam only where something crosses a process, a clock or a failure boundary: persistence, time, messaging, IDs and outside calls.
- **Designing the distributed version first** in an LLD round means the domain model and tests never get written. Those are the graded work.
- **Hiding the network behind a local-looking method** ("location transparency"). The caller must still be able to see that `save` can fail with an unknown result. Show it in the return type or a documented exception.

### When NOT to distribute at all

First check whether one instance can carry the load (do the estimate: [SD 03](../../pocs/SystemDesign-poc/03-back-of-envelope-estimation/)). If it can, one process with a hot standby (a spare ready to take over) avoids every row of the table above. "We don't need distributed locking because there is one writer per key" is a staff answer, not a dodge.

---

## 4. Official References

- Martin Kleppmann, *How to do distributed locking* (fencing tokens; why Redlock lacks them): https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html
- `System.nanoTime` ("only meaningful… within the same instance of a JVM"): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/System.html#nanoTime()
- `java.time.Clock` (injectable wall-clock time): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Clock.html
- Alistair Cockburn, *Hexagonal Architecture* (ports and adapters): https://alistair.cockburn.us/hexagonal-architecture/
- Peter Deutsch et al., *Fallacies of distributed computing*: https://en.wikipedia.org/wiki/Fallacies_of_distributed_computing
- Martin Kleppmann, *Designing Data-Intensive Applications*, ch. 8 "The Trouble with Distributed Systems" (clocks, process pauses, fencing)

---

## 5. Key Takeaways

1. A single-process design silently relies on one heap, one lock domain, one clock, and calls that return or throw. Distribution removes all four.
2. Put persistence, time, messaging, IDs and outside calls behind ports. Nothing else.
3. A seam only works if its **signature admits distributed failure**: version conflicts, timeouts, duplicates.
4. Prefer conditional writes to distributed locks. If you lock, you need **fencing tokens**.
5. Never compare `nanoTime` across JVMs. Never order events by wall-clock timestamps from different hosts.
6. Retries need idempotency. "Exactly-once" means at-least-once plus idempotent processing.
7. In the round, code the in-memory adapters and *say* the distributed swap in one sentence per seam.
8. Sometimes the right scale answer is "one writer per key", with no distribution at all.

---

[← Topic 12: Common Mistakes](../12-common-mistakes/) · [Index](../README.md) · [Topic 14: Parking Lot →](../14-parking-lot/)

**Self-grade: ADEQUATE.** The seam table, port-design rules, fencing argument and over-correction gaps meet the bar, but there are no concrete numbers (latency/RTT budgets) and no runnable demo. It's a bridge topic that defers depth to SystemDesign-poc by design.
