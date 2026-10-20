# Topic 07: Concurrency Primitives — Picking One, and Making Check-and-Act Atomic

Most concurrency bugs in LLD rounds aren't exotic. They come from code that checks something
("is the seat free?", "is the count under the limit?") and then acts on the answer, while another
thread changes the answer in between. Swapping in a "thread-safe" collection doesn't fix it, and
adding `volatile` doesn't either. The staff skill is to name the **invariant**, see the **gap**
between check and act, and pick the **smallest primitive** that closes it.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [Key Takeaways](#5-key-takeaways)

---

## 1. The Problem

Three bugs that show up again and again in LLD code. All three are in this module, each with a
test that reproduces the bug:

| Bug | Looks safe because… | What actually happens |
|---|---|---|
| **Check-then-act** on a `ConcurrentHashMap` (`containsKey` → `put`) | "it's a concurrent map" | Two users both told they booked seat 7, and the second silently overwrites the first |
| **`volatile long count; count++`** | "volatile makes it thread-safe" | `++` is read-add-write. Two threads read 41 and both write 42, so hits are lost |
| **`ConcurrentHashMap<K, ArrayList<V>>`** + `computeIfAbsent(k, …).add(v)` | "computeIfAbsent is atomic" | The `add` runs *after* the map lock is released. Items get lost and the list is left with **`null` slots** (seen here as an NPE on read) |

The common root: **a thread-safe component makes each call atomic, never a sequence of calls.**
An invariant that spans two calls (check + act, or map + value) needs one primitive covering both.

---

## 2. How It Solves It

### Step 1: name the shared state and the invariant out loud

> "The shared state is `seat → holder`. The invariant is *at most one holder per seat, and
> whoever we tell 'you won' is the holder*. The check and the act must be one atomic step."

Once it's stated like that, the primitive almost picks itself.

### Step 2: the primitive selection table

| You need | Use | Not |
|---|---|---|
| Insert-if-absent / one winner | `map.putIfAbsent`, `AtomicReference.compareAndSet(null, x)` | `containsKey` + `put` |
| Read-modify-write of **one** value in a map | `map.compute` / `merge` (runs under that key's bin lock) | `get` + `put` |
| A counter | `LongAdder` (write-heavy), `AtomicLong` (need CAS or the new value) | `volatile` + `++` |
| Multi-field state for one entity | `synchronized` / `ReentrantLock` on the entity, **or** an immutable state object + `AtomicReference` CAS loop | separate atomics per field (each atomic, combination not) |
| A collection as a map value | concurrent value (`ConcurrentHashMap.newKeySet()`, `ConcurrentLinkedQueue`), **or** do the whole update inside `compute` and keep reads to snapshots | `ArrayList` / `HashSet` values |
| Read-mostly, tiny, iterate often (listeners) | `CopyOnWriteArrayList` (every write copies the array, so O(n)) | COW for anything that grows or is written often |
| Visibility of a flag/reference set by one thread | `volatile` | locks (overkill) |
| Bound concurrency (N at a time) | `Semaphore` | a pool sized as a "limit" |
| Hand-off between threads | `BlockingQueue` ([Topic 09](../09-executors-and-backpressure/)) | `wait`/`notify` by hand |
| Read-heavy with rare writes | `ReentrantReadWriteLock`. Measure first, and **never upgrade read → write** ([Topic 12](../12-common-mistakes/)) | assuming it beats a plain lock (often it doesn't) |
| An invariant across **two** objects | lock ordering, or one lock over both ([Topic 08](../08-multi-object-atomicity/)) | locking each object separately |

### Step 3: the fixes, in code

```java
// Seat booking — one atomic call that checks AND acts
return holders.putIfAbsent(seat, user) == null;          // exactly one caller sees null

// Counter — atomic increment, contention-friendly
count.increment();                                        // LongAdder

// Map of collections — make the VALUE concurrent too
index.computeIfAbsent(tag, t -> ConcurrentHashMap.newKeySet()).add(item);
```

| File | Role |
|---|---|
| [`CheckThenActSeatMap`](src/main/java/com/lld/concurrency/CheckThenActSeatMap.java) → [`AtomicSeatMap`](src/main/java/com/lld/concurrency/AtomicSeatMap.java) | bug → fix |
| [`VolatileHitCounter`](src/main/java/com/lld/concurrency/VolatileHitCounter.java) → [`LongAdderHitCounter`](src/main/java/com/lld/concurrency/LongAdderHitCounter.java) | bug → fix |
| [`ArrayListTagIndex`](src/main/java/com/lld/concurrency/ArrayListTagIndex.java) → [`ConcurrentSetTagIndex`](src/main/java/com/lld/concurrency/ConcurrentSetTagIndex.java) | bug → fix |
| [`RaceWindow`](src/main/java/com/lld/concurrency/RaceWindow.java) | test seam that forces the bad interleaving |

### Step 4: tests that catch the bug, not tests that hope to

A race test that "usually passes" proves nothing. Measured: the broken seat map *without* the
hook, raced by 2 threads on 1 seat, double-booked in only **2 of 20** runs, so that test is
green 90% of the time against broken code. At 32 threads × 1,000 seats it failed 20 of 20. There
are two techniques, and this module uses both:

1. **Force the interleaving (deterministic).** The broken seat map takes a `RaceWindow` hook
   between its check and its act. The test passes a `CyclicBarrier(2)`, so the barrier opens only
   once **both** threads have checked. Both are then guaranteed to act on a stale "free". The bug
   reproduces on every run, with no sleeps and no luck. The fixed class has no gap, so it takes no
   hook.

   ```
   T1: containsKey(7)=false ──┐                 ┌── put(7,"user-0") → true
                              ├─ barrier(2) ────┤
   T2: containsKey(7)=false ──┘                 └── put(7,"user-1") → true   ← double booking
   ```

2. **Hammer with a start gate (probabilistic, bounded retries).** When the race is inside the JDK
   (`count++`, `ArrayList.add`) there's nowhere to put a hook. The test runs 8 threads × 100k ops
   behind a `CountDownLatch` start gate and retries up to 10 times. The *fix* test asserts the
   exact count.

Production-grade alternatives for (2): [jcstress](https://github.com/openjdk/jcstress) for JMM
litmus tests, and [Lincheck](https://github.com/JetBrains/lincheck) for linearizability checking.
Both are too heavy for a 45-minute round. Name them, don't use them.

Run it: [`commands.md`](commands.md).

---

## 3. The Gaps

### Misconceptions

- **"ConcurrentHashMap makes my code thread-safe."** It makes *each map operation* atomic. Your
  invariant is almost always about a *sequence*. Use the atomic compound methods (`compute`,
  `merge`, `putIfAbsent`, `computeIfAbsent`) or a lock.
- **"`volatile` fixes races."** It fixes *visibility* (a write is seen by later reads) and
  ordering. It never makes read-modify-write atomic. Reach for it for a `stop` flag or a
  safely published immutable reference, and for nothing else.
- **"`computeIfAbsent(...).add(...)` is atomic."** Only the `computeIfAbsent` part is. The
  returned value is mutated outside any lock.
- **"`synchronized` is slow, so I'll use lock-free."** Uncontended `synchronized` is cheap.
  A CAS loop on a hot variable can be *worse* than a lock under heavy contention, because every
  failed CAS is wasted work. Start with the simplest correct thing and measure. `LongAdder`
  exists precisely because `AtomicLong` degrades under write contention.
- **"Lambdas inside `compute` can do anything."** Keep them short, and **never** touch another
  key of the same map inside one. The Javadoc warns that this can deadlock or throw
  `IllegalStateException("Recursive update")`.
- **"CopyOnWriteArrayList is the safe list."** It's safe and O(n) per write. It's right for a
  listener list (tiny, written rarely, iterated constantly) and wrong for an index, a queue, or
  anything that grows. It also doesn't fix check-then-act: `if (!contains) add` still races. Use
  `addIfAbsent` ([Topic 12](../12-common-mistakes/)).

### Failure modes and their symptoms

| Symptom in prod | Likely cause |
|---|---|
| Totals slightly low, never high | lost updates (`++` on a non-atomic field) |
| Two customers with one seat/ticket/coupon | check-then-act |
| Rare `NullPointerException` / `ArrayIndexOutOfBoundsException` from inside `ArrayList`/`HashMap` | non-thread-safe value mutated concurrently |
| `ConcurrentModificationException` | iterating a plain collection that another thread writes |
| Throughput collapses under load, CPU idle | one global lock on the hot path (serialised); see [Topic 21](../21-rate-limiter/) for per-key locking |

### When NOT to reach for a concurrency primitive

- **When you can confine state to one thread** (actor/event-loop style, a single-writer queue).
  No shared mutable state means no races. This is often the cleanest answer for an
  order-matching engine.
- **When the data can be immutable.** Publish a new immutable snapshot through one `volatile`
  or `AtomicReference` write (config reload, routing tables).
- **When the interviewer said single-threaded.** Say "I'll keep it single-threaded as agreed, and
  here's the one place I'd add a lock if that changes." Don't sprinkle `synchronized` everywhere.

### Interview move

When you write any `if (…) { mutate }` on shared state, pause and say: *"This is a check-then-act.
Here's the invariant, and here's how I make it atomic."* That single sentence is the strongest
concurrency signal available in a 45-minute round.

---

## 4. Official References

- `java.util.concurrent` package summary (memory consistency effects and the happens-before table):
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/package-summary.html
- `ConcurrentHashMap` (atomicity of `compute`/`merge`/`putIfAbsent`, and the no-other-keys rule):
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html
- `LongAdder` (when to prefer it over `AtomicLong`):
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/atomic/LongAdder.html
- `CopyOnWriteArrayList`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CopyOnWriteArrayList.html
- JLS §17.4, the Java Memory Model (what `volatile` does and doesn't guarantee):
  https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html#jls-17.4
- Aleksey Shipilëv, *Java Memory Model Pragmatics*: https://shipilev.net/blog/2014/jmm-pragmatics/
- jcstress (OpenJDK concurrency stress tests): https://github.com/openjdk/jcstress
- Brian Goetz et al., *Java Concurrency in Practice* (book), ch. 2 (atomicity and compound actions)
  and ch. 5 (concurrent collections).

---

## 5. Key Takeaways

1. A thread-safe collection makes **calls** atomic, not **sequences** of calls.
2. Say the invariant, find the gap between check and act, then close it with *one* atomic
   operation (`putIfAbsent`, `compute`, `merge`, CAS) or one lock.
3. `volatile` = visibility only. `++` on a volatile still loses updates.
4. A concurrent map of non-concurrent values is a race. Make the value concurrent, or do the
   whole update inside `compute`.
5. `LongAdder` for hot counters, `AtomicLong` when you need CAS or the new value.
6. COW lists are for tiny, rarely written lists (listeners). Writes are O(n).
7. Test races deterministically: force the interleaving with a barrier at a seam. Where you
   can't, hammer behind a start gate and assert exact totals for the fix.
8. Confinement and immutability beat clever locking when the problem allows them.

---

**Self-grade: STRONG.** Three real bug classes with a reproducing test each (one deterministic,
two bounded-probabilistic), a primitive-selection table with "not" alternatives, symptoms, and
when-not-to. One gap: `StampedLock` and `ReadWriteLock` are only covered by pointers to
Topic 12, not coded here.

[← Topic 06: State Machines](../06-state-machines/) · [Index](../README.md) · [Topic 08: Multi-Object Atomicity →](../08-multi-object-atomicity/)
