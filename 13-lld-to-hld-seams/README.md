# Topic 13: From LLD to HLD — Where the Seams Go

Every LLD round ends with some version of "now it runs on 40 machines". The in-process design
you just coded starts lying at that moment:

- the `ConcurrentHashMap` is 40 different maps
- the `ReentrantLock` protects nothing across JVMs
- `System.nanoTime()` from two machines can't be compared
- the method call that "returns or throws" can now also *time out with an unknown outcome*

The staff skill isn't to build the distributed version in a coding round. It's to have **put
the seams in the right places** so that swapping the implementation is local, and to say in two
sentences what changes behind each seam.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [Key Takeaways](#5-key-takeaways)

---

## 1. The Problem

An in-process design leans on guarantees that only hold inside one JVM:

| In-process guarantee you used | Why it held | What breaks across processes |
|---|---|---|
| One copy of state | one heap | K instances = K copies. The rate limiter enforces **K × limit** ([Topic 21](../21-rate-limiter/)) |
| Locks give mutual exclusion | one memory model, one scheduler | a JVM lock means nothing to the other 39. A distributed lock's holder can pause (GC, network) past its lease and still write |
| Time is monotonic and comparable | one `nanoTime` origin | `nanoTime` origins differ per JVM. Wall clocks skew and jump, so ordering by timestamp is unsafe |
| A call returns or throws | same address space | a third outcome appears: **timed out, effect unknown**. Retries follow, then duplicates |
| An event listener runs once | in-memory observer | brokers deliver **at least once**, possibly reordered |
| A cache is the truth | it's the only copy | N caches + a DB means invalidation, staleness and a thundering herd |
| `AtomicLong` IDs are unique | one counter | each instance mints `1, 2, 3…`. Collisions |
| Multi-object update under one lock | one lock covers both | two services or DBs means a distributed transaction, a saga or an outbox |

The interviewer is checking whether you know which of these your design depends on. A design
that hard-codes them (`new HashMap<>()` inside the domain, `System.currentTimeMillis()`
everywhere) can't evolve without a rewrite.

---

## 2. How It Solves It

### Put each in-process assumption behind a port

```java
public interface Clock { long nowNanos(); }                    // Ticker in Topic 21; java.time.Clock for wall time
public interface OrderRepository {
    Optional<Order> find(OrderId id);
    void save(Order order, long expectedVersion);              // optimistic concurrency: the version travels with the write
}
public interface EventPublisher { void publish(DomainEvent e); }  // in-memory list today; outbox + broker tomorrow
public interface IdGenerator { OrderId next(); }                  // UUIDv7 today; nothing to coordinate
```

The domain (aggregates, state machines, invariants) depends only on these. The LLD round ships
in-memory implementations, and the HLD answer swaps them out. Rules for a seam that survives
the swap:

1. **The signature must admit the distributed failure modes.** `save(order, expectedVersion)`
   can express "someone else wrote first" (a conflict), which an in-memory map never needs but a
   database does. A `void save(order)` designed for a `HashMap` can't express it, and that
   omission is the leak.
2. **No in-process objects cross the seam.** Pass values (IDs, records). Never pass a lock, a
   live mutable entity shared by reference, or a callback the far side must invoke.
3. **Time comes in, it isn't fetched.** Domain methods take `now` (or a `Clock`), which is what
   makes them testable (Topic 10) *and* lets the distributed version use server time.
4. **Mutations carry an idempotency handle.** A key, a version or a sequence number, so that
   at-least-once delivery is harmless (Topic 11).

### What changes behind each seam

| LLD component | In-process (what you code) | At scale (what you *say*) | Deep dive |
|---|---|---|---|
| Per-key state (rate limiter, counters) | `ConcurrentHashMap.compute` | central store with an atomic script (Redis + Lua), or shard keys to owners by consistent hashing | [SD 64](../../pocs/SystemDesign-poc/64-rate-limiting-and-admission-control/), [SD 41](../../pocs/SystemDesign-poc/41-partitioning-schemes/), [Topic 29](../29-consistent-hashing/) |
| Mutual exclusion | `ReentrantLock`, `synchronized` | prefer **conditional writes** (version check, `UPDATE … WHERE version = ?`). If you need a lock: a lease from a consensus-backed service **plus fencing tokens** checked by the resource | [SD 49](../../pocs/SystemDesign-poc/49-locks-and-coordination-services/), [SD 09](../../pocs/SystemDesign-poc/09-quorums-split-brain-and-fencing/) |
| Time | injected `Ticker` / `Clock` | server-side time at the single writer; logical/hybrid clocks for ordering; never compare `nanoTime` across hosts | [SD 08](../../pocs/SystemDesign-poc/08-time-clocks-and-ordering/) |
| Method calls | direct call | timeout + retry with backoff + idempotency key; circuit breaker | [SD 10](../../pocs/SystemDesign-poc/10-idempotency-and-exactly-once/), [Topic 27](../27-circuit-breaker-and-retry/) |
| Observer / event bus | in-memory listener list | broker; at-least-once; per-key ordering via partitions; idempotent consumers; outbox for "DB write + publish" | [SD 50](../../pocs/SystemDesign-poc/50-messaging-semantics/), [SD 52](../../pocs/SystemDesign-poc/52-reliability-in-messaging/) |
| Cache | `LinkedHashMap` LRU (Topic 17) | shared cache tier; invalidation strategy; stampede protection; TTL as a safety net | [SD 25](../../pocs/SystemDesign-poc/25-caching-strategies/), [SD 27](../../pocs/SystemDesign-poc/27-invalidation-and-consistency/) |
| ID generation | `AtomicLong` or UUIDv7 | UUIDv7 (no coordination) or Snowflake (node-ID assignment, clock-backwards guard) | [SD 87](../../pocs/SystemDesign-poc/87-distributed-id-generation/), [Topic 28](../28-id-generator/) |
| Multi-object atomicity | one lock, lock ordering (Topic 08) | one DB transaction if one DB. Otherwise saga or TCC with compensations | [SD 45](../../pocs/SystemDesign-poc/45-distributed-transactions/), [SD 46](../../pocs/SystemDesign-poc/46-sagas-tcc-reliability/) |

### Why "just add a distributed lock" is the wrong reflex

A lease-based lock has a lifetime. The holder can stall past it: a stop-the-world GC, a paused
VM, a slow network. Then it wakes up and writes, while a second holder already has the lock.
Kleppmann's fix is a **fencing token**: a strictly increasing number issued with each lease and
checked *by the storage* on every write, which rejects any older token. Without that check the
lock is an efficiency optimisation (it avoids duplicate work), not a correctness guarantee.
Prefer designs where the storage's own conditional write is the guard.

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

One sentence per seam, then back to the problem. Building a Redis client in an LLD round
burns the time you need for tests.

---

## 3. The Gaps

### Common misconceptions

| "I thought…" | Actually |
|---|---|
| "An interface makes it swappable" | Only if the signature already admits conflicts, timeouts and duplicates. A `HashMap`-shaped interface leaks |
| "A Redis lock (`SET NX PX`) makes it safe" | It's safe only with fencing at the resource. Otherwise a paused holder can still write |
| "We'll use timestamps to order events" | Clock skew between hosts reorders them. Use a single writer's sequence, or partition ordering |
| "The broker guarantees exactly-once" | End-to-end, you get at-least-once delivery + idempotent processing. Design consumers for duplicates |
| "Retries make it reliable" | Retries without idempotency make it *duplicate*. Retries without backoff and limits make it a retry storm |
| "Sticky sessions keep state local, problem solved" | Until rebalancing, a deploy or a node loss moves keys. State must be rebuildable or externalised |

### Over-correcting is also a failure

- **A port for everything** (`StringFormatter`, `ListFactory`) is ceremony that hides the domain.
  Seam only what crosses a process, a clock, or a failure boundary: persistence, time,
  messaging, IDs, external calls.
- **Designing the distributed version first** in an LLD round means the domain model and tests
  never get written, and those are the graded artefacts.
- **Hiding the network behind a local-looking method** ("location transparency"). The caller
  must still be able to see that `save` can fail ambiguously, through the return type or a
  documented exception.

### When NOT to distribute at all

If one instance comfortably handles the load (do the estimate:
[SD 03](../../pocs/SystemDesign-poc/03-back-of-envelope-estimation/)), a single process with a
hot standby avoids every row of the table above. "We don't need distributed locking because
there is one writer per key" is a staff answer, not a dodge.

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

1. In-process designs silently rely on one heap, one lock domain, one clock, and calls that
   return or throw. Distribution removes all four.
2. Seam persistence, time, messaging, IDs and external calls behind ports. Nothing else.
3. A seam only works if its **signature admits distributed failure**: version conflicts,
   timeouts, duplicates.
4. Prefer conditional writes to distributed locks. If you lock, you need **fencing tokens**.
5. Never compare `nanoTime` across JVMs or order events by wall-clock timestamps from different
   hosts.
6. Retries require idempotency. "Exactly-once" = at-least-once + idempotent processing.
7. In the round, code the in-memory adapters and *say* the distributed swap in one sentence per
   seam.
8. Sometimes the right scale answer is "one writer per key", with no distribution needed.

---

[← Topic 12: Common Mistakes](../12-common-mistakes/) · [Index](../README.md) · [Topic 14: Parking Lot →](../14-parking-lot/)

**Self-grade: ADEQUATE.** The seam table, port-design rules, fencing argument and
over-correction gaps meet the bar, but there are no concrete numbers (latency/RTT budgets) and
no runnable demo. It's a bridge topic that defers depth to SystemDesign-poc by design.
