# Topic 12: Common Mistakes, Mined From Real Reviews (and the Fixes That Were Also Wrong)

The old review set in this folder (`review-*_old.md`, `REVIEW-SUMMARY_old.md`) graded 11 generated
LLD solutions, and the same defects came up again and again. This topic turns that catalogue into
code. Every mistake gets a deterministic test that reproduces it, and a fix with a test that
proves it. It also corrects the reviews themselves. Several of their recommended fixes were
incomplete or wrong in ways an interviewer would catch, and **knowing why the "obvious fix" is
wrong is the staff signal**.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [Key Takeaways](#5-key-takeaways)

---

## 1. The Problem

What the old reviews found, how often, and where this guide now covers each one:

| Mistake | Frequency in old reviews | Covered |
|---|---|---|
| `throw new Exception(...)`, no error model | 8 of 11 | [Topic 04](../04-interface-first-api-and-errors/) |
| Check-then-act (`if available → acquire`) | 7 of 11 systems | [Topic 07](../07-concurrency-primitives/) (deterministic barrier test) |
| `ConcurrentHashMap<K, ArrayList<V>>` | 6 of 11 | [Topic 07](../07-concurrency-primitives/) |
| No listener error isolation | 6 of 11 | **M7** here |
| Mutability leaks (returning internal lists) | 5 of 11 | **M8** here |
| "Missing `volatile`" on shared fields | 4 of 11 | **M9** here, plus [Topic 07](../07-concurrency-primitives/) |
| `double` for money | 10 of 11 (of those handling currency) | **M6** here, plus [Topic 11](../11-money-ids-idempotency/) |
| Read → write lock "upgrade" in the LRU cache | LRU (graded "critical") | **M1–M3** here |
| Multi-object transfers | ATM, wallet | [Topic 08](../08-multi-object-atomicity/) |

Frequencies are as reported in `REVIEW-SUMMARY_old.md`. They count the 11 systems actually reviewed,
not the 33 that `FINAL-REVIEW-REPORT_old.md` extrapolated to.

### The fixes that were themselves wrong

| Review said | Problem with that fix | Correct move |
|---|---|---|
| "Replace ArrayList with `CopyOnWriteArrayList`" (16 systems) | Every write copies the whole array (O(n)). It's also **still check-then-act unsafe**: `if (!contains) add` duplicates (**M5**) | Fits listeners only: tiny, rarely written. Otherwise use `ConcurrentHashMap.newKeySet()`, `addIfAbsent`, or a lock around the compound op |
| "Add `volatile` for visibility" | Visibility, not atomicity. Two related volatile fields can be read **half-updated** (**M9**) | Make the related state one value: one `AtomicReference` CAS, or one lock |
| Money conservation test with `assertEquals(a, b, 0.01)` | A one-cent error lands just under or just over 0.01 in binary FP. **Measured:** it catches the $100.00 / 3 leak and misses $10.00 / 3 (**M6**) | `long` minor units, `assertEquals(long, long)`, largest-remainder split |
| LRU "Option 2: StampedLock optimistic read" | LRU `get` **reorders the list, so it's a write**. The proposed code takes the write lock on every hit anyway, which is strictly more work than "write lock only" | One plain lock (**M2**). Under measured contention use Caffeine, which buffers reads |
| LRU "Option 3: segmented, best scalability" | It's no longer an LRU. Per-segment capacity evicts early, and eviction is per-segment, not global (**M3**) | Offer it as an explicit trade-off, never as a free win |
| Original LRU diagnosis: "lock upgrade not supported" | Half right. The code *released* then re-acquired, which avoids the self-deadlock. The real defect is **no re-check** after re-acquiring (**M1**) | Release, acquire, **re-check**. Or `computeIfAbsent` |
| "`Collections.unmodifiableList` fixes the leak" | It's a **live view**: the caller's copy changes under them, and iterating it while the owner writes throws `ConcurrentModificationException` (**M8**) | `List.copyOf` snapshot, and copy in record compact constructors |
| "Wrap each listener in try/catch" | Right, but incomplete. It said nothing about where the error goes, or about unsubscribe-during-dispatch, which **silently skips a listener** (**M7**) | COW list for listeners, per-listener catch, *report* to an injected handler |
| PCI / SEC / "$10M risk" severity framing | Not an interview signal | State the invariant that breaks and the user-visible symptom |

---

## 2. How It Solves It

### M1: read → write lock "upgrade"

```java
lock.readLock().lock();
lock.writeLock().lock();     // blocks FOREVER, even in one thread: the write lock waits for all readers, including you
```

- `ReentrantReadWriteLock` supports **downgrade** (write → read), never upgrade. The test proves
  it without hanging: holding the read lock, `writeLock().tryLock()` returns `false`.
- The workaround people write is release-read then acquire-write. That avoids the deadlock and
  opens a gap. In `NoRecheckLazyCache`, two threads miss, meet in the gap (a `CyclicBarrier`),
  and **both load**. `RecheckingLazyCache` re-checks under the write lock and loads once. This is
  the `CachedData` example in the JDK Javadoc.
- Usually the real answer is `ConcurrentHashMap.computeIfAbsent`: one call, per-key, no RW lock.

### M2: in an LRU, `get` is a write

`LinkedHashMap(…, accessOrder = true)` relinks the entry on every `get`. The test shows this on a
single thread: calling `get` while iterating throws `ConcurrentModificationException`. So a read
lock around `get` lets two "readers" relink the same list concurrently. `LruCache` uses one plain
`ReentrantLock`, with `removeEldestEntry` for eviction. A test runs 8 threads × 50k mixed ops
and the size stays exactly at capacity.

### M3: segmented LRU is a trade-off

Capacity 4, 2 segments. Keys 0, 2 and 4 all hash to segment 0:

```
true LRU (cap 4):   [0, 2, 4]         size 3, nothing evicted
segmented (2 × 2):  seg0 [2, 4]  seg1 []     key 0 evicted while half the cache is empty
```

A second test shows **order** loss. Key 1 is the oldest entry overall, but it survives while a
newer key in segment 0 is evicted.

### M4: `StampedLock` optimistic read, done right

The correct pattern (from the `StampedLock` Javadoc) is to read fields **into locals**, then
`validate(stamp)`, then fall back to a read lock if validation fails. Use the locals only after
validating. `UnvalidatedOptimisticPoint` skips `validate`, and a write inside the reader's gap
returns `{1, 20}`, a pair that never existed. The test is deterministic and single-threaded,
because an optimistic read holds no lock, so the writer can run inside the gap on the same
thread. `StampedLock` is also **not reentrant**. Use it only for read-mostly state where a read
doesn't mutate anything, which rules out an LRU.

### M5: `CopyOnWriteArrayList` and check-then-act

The barrier forces two threads through `contains` before either `add`s, and the list ends up with
the URL twice. `addIfAbsent` does the check and the add under the list's lock.

### M6: money as `double`

| Amount / 3, rounded per share | Sum | Error | Passes `assertEquals(total, sum, 0.01)`? |
|---|---|---|---|
| $10.00 | 9.99 | −1¢ | **yes** (diff 0.009999999999999787) |
| $20.00 | 20.009999999999998 | +1¢ | **yes** |
| $100.00 | 99.99 | −1¢ | no (diff 0.010000000000005116) |
| $1,000.00 | 999.99 | −1¢ | **yes** |

Measured with jshell on JDK 21. The same one-cent bug passes or fails the tolerance test depending
on the amount. The fix is `MoneySplit`: `long` minor units with the **largest-remainder** method.
Floors first, then the leftover units go to the largest fractional remainders (ties to the lower
index, for determinism). `equally(10_000, 3)` gives `[3334, 3333, 3333]`. A property test runs
10,000 random totals and weight sets and checks that the sum is **exact** and that every share is
within 1 unit of its exact proportion. Overflow throws `ArithmeticException` instead of wrapping.

### M7: listener isolation

| Scenario | `NaiveEventBus` (`ArrayList`, no catch) | `IsolatingEventBus` |
|---|---|---|
| Listener 2 of 3 throws | listener 3 never runs; exception escapes into the **publisher** | all run; failure goes to the injected error handler |
| One-shot listener unsubscribes itself, 2 listeners | **the last listener is silently skipped**, with no exception (iterator sees `cursor == size`) | both run; removal takes effect next publish |
| Same, 3 listeners | `ConcurrentModificationException` | fine |

The silent skip in the middle row was found by testing. The first version of this test expected a
CME and failed.

### M8: mutability leaks

- `LeakyCart.items()` returns the internal list, so a caller can `add` past the max-items
  invariant.
- `itemsView()` (`unmodifiableList`) blocks writes, but it's live: the caller's "read-only" list
  grows, and iterating it while the cart changes throws a CME.
- `Cart.items()` returns `List.copyOf(...)`, which is unmodifiable and detached.
- `OrderSnapshot` is a **record** that copies its list in the compact constructor. Without that,
  the "immutable" record shares the caller's `ArrayList`. `List.copyOf` also rejects `null`
  elements.

### M9: two `volatile` fields aren't one state

`TwoVolatileSpot` writes `occupied = true`, then `vehicle = v`. A reader in between sees
**"occupied, by nobody"**. Each field is visible; the pair isn't atomic. `AtomicSpot` makes the
occupant reference *be* the state (`null` = free), so one `compareAndSet(null, v)` both checks and
acts. For more than one field, put them in an immutable record and CAS the record.

### Code and tests

| Mistake | Bug | Fix | Test |
|---|---|---|---|
| M1 | [`NoRecheckLazyCache`](src/main/java/com/lld/mistakes/NoRecheckLazyCache.java) | [`RecheckingLazyCache`](src/main/java/com/lld/mistakes/RecheckingLazyCache.java) | [`ReadWriteLockTest`](src/test/java/com/lld/mistakes/ReadWriteLockTest.java) |
| M2, M3 | [`SegmentedLruCache`](src/main/java/com/lld/mistakes/SegmentedLruCache.java) (trade-off) | [`LruCache`](src/main/java/com/lld/mistakes/LruCache.java) | [`LruCacheTest`](src/test/java/com/lld/mistakes/LruCacheTest.java) |
| M4 | [`UnvalidatedOptimisticPoint`](src/main/java/com/lld/mistakes/UnvalidatedOptimisticPoint.java) | [`OptimisticPoint`](src/main/java/com/lld/mistakes/OptimisticPoint.java) | [`OptimisticReadTest`](src/test/java/com/lld/mistakes/OptimisticReadTest.java) |
| M5 | [`CowCheckThenActRegistry`](src/main/java/com/lld/mistakes/CowCheckThenActRegistry.java) | [`CowAddIfAbsentRegistry`](src/main/java/com/lld/mistakes/CowAddIfAbsentRegistry.java) | [`CopyOnWriteTest`](src/test/java/com/lld/mistakes/CopyOnWriteTest.java) |
| M6 | [`DoubleSplit`](src/main/java/com/lld/mistakes/DoubleSplit.java) | [`MoneySplit`](src/main/java/com/lld/mistakes/MoneySplit.java) | [`MoneySplitTest`](src/test/java/com/lld/mistakes/MoneySplitTest.java) |
| M7 | [`NaiveEventBus`](src/main/java/com/lld/mistakes/NaiveEventBus.java) | [`IsolatingEventBus`](src/main/java/com/lld/mistakes/IsolatingEventBus.java) | [`EventBusTest`](src/test/java/com/lld/mistakes/EventBusTest.java) |
| M8 | [`LeakyCart`](src/main/java/com/lld/mistakes/LeakyCart.java) | [`Cart`](src/main/java/com/lld/mistakes/Cart.java), [`OrderSnapshot`](src/main/java/com/lld/mistakes/OrderSnapshot.java) | [`MutabilityTest`](src/test/java/com/lld/mistakes/MutabilityTest.java) |
| M9 | [`TwoVolatileSpot`](src/main/java/com/lld/mistakes/TwoVolatileSpot.java) | [`AtomicSpot`](src/main/java/com/lld/mistakes/AtomicSpot.java) | [`ParkingSpotTest`](src/test/java/com/lld/mistakes/ParkingSpotTest.java) |

30 tests, green 3 runs in a row. Every bug test is deterministic: a barrier, a same-thread
probe in a `Gap`, or plain single-threaded behaviour. None of them relies on luck. Run them:
[`commands.md`](commands.md).

---

## 3. The Gaps

### Misconceptions

- **"Thread-safe collection = thread-safe code."** Calls are atomic, sequences aren't (M5, and
  Topic 07).
- **"A read-write lock is faster."** Only when reads truly don't mutate, are long enough to
  amortise the more expensive lock, and outnumber writes heavily. Measure it. For short critical
  sections a plain lock often wins.
- **"Optimistic reads are free."** They're free when they succeed. Every failed `validate` costs
  the optimistic attempt plus a real read lock, and the code is harder to get right (M4).
- **"Tolerance-based asserts are fine for money."** They make the test depend on floating-point
  luck (M6). Money tests should be exact.
- **"Records are immutable."** Only shallowly. A `List` component is as mutable as the list you
  passed in (M8).
- **"Swallow listener exceptions so the publisher is safe."** Swallowing hides the failure.
  Isolate it **and report it** (M7).

### Review hygiene: how to write a review that holds up

The old reviews graded 22 of 33 systems by extrapolation and quoted performance numbers no one
had measured. For your own review answers in an interview ("what's wrong with this code?"):

1. Name the **invariant** that breaks, not a category ("thread-safety issue").
2. Give the **interleaving** or input that breaks it.
3. Propose the smallest fix **and say what it costs** (O(n) writes, serialisation, lost LRU order).
4. Say how you'd **prove** it: a deterministic test, not "run it 1,000 times".

### When NOT to apply these fixes

- **Single-threaded by agreement.** If the interviewer said single-threaded, don't add locks.
  Name the one place you would.
- **Library available.** In production, use Caffeine for caches and an established bus. Write
  your own only to show you understand what the library does for you.

### Interview move

When asked to review code, go through M1–M9 as a checklist: compound ops, lock scope, RW/upgrade,
returned collections, money type, listener loop, related fields. Then state the cost of each fix.

---

## 4. Official References

- `ReentrantReadWriteLock` (no upgrade; downgrade allowed; the `CachedData` re-check example):
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/ReentrantReadWriteLock.html
- `StampedLock` (optimistic read pattern; not reentrant):
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/StampedLock.html
- `LinkedHashMap` (access order makes `get` a structural modification; `removeEldestEntry`):
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/LinkedHashMap.html
- `CopyOnWriteArrayList.addIfAbsent`:
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CopyOnWriteArrayList.html#addIfAbsent(E)
- `List.copyOf` vs `Collections.unmodifiableList` (snapshot vs view):
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/List.html#copyOf(java.util.Collection)
- JLS §8.10, records (the compact canonical constructor):
  https://docs.oracle.com/javase/specs/jls/se21/html/jls-8.html#jls-8.10
- JUnit `Assertions.assertEquals(double, double, double)` (delta semantics):
  https://docs.junit.org/current/api/org.junit.jupiter.api/org/junit/jupiter/api/Assertions.html
- Caffeine design (read buffers instead of read locks for LRU-ish policies):
  https://github.com/ben-manes/caffeine/wiki/Design
- Largest remainder method: https://en.wikipedia.org/wiki/Largest_remainders_method

---

## 5. Key Takeaways

1. RW locks never upgrade. Release, acquire, and **re-check**, or use `computeIfAbsent`.
2. In an LRU, `get` is a write. One plain lock is the correct baseline.
3. A segmented LRU isn't an LRU. Present it as a trade-off.
4. Optimistic read means copy, then `validate`, then fall back. Never use the values before
   validating.
5. COW lists are for listeners. They're O(n) per write and still check-then-act unsafe.
6. Money is `long` minor units, splits use largest remainder, and asserts are exact. A 0.01 delta
   is luck.
7. Listener loops isolate **and report** failures, and iterate over a snapshot.
8. Return snapshots (`List.copyOf`), not views, and copy in record constructors.
9. Related fields form one state. Use one CAS or one lock, never two volatiles.
10. A review finding = invariant + interleaving + fix + cost + proof.

---

**Self-grade: STRONG.** Nine mistakes mined from real reviews, each with a deterministic
reproducing test and a proven fix. A correction table for the reviews' own wrong fixes, two of
them found only by measuring (the FP-tolerance luck and the silent listener skip). Review hygiene
and a checklist for interview code reviews. Gap: no JMH numbers for RW lock vs plain lock vs
Caffeine. The claim that RW locks often lose is stated, not measured here.

[← Topic 11: Money, IDs & Idempotency](../11-money-ids-idempotency/) · [Index](../README.md) · [Topic 13: From LLD to HLD →](../13-lld-to-hld-seams/)
