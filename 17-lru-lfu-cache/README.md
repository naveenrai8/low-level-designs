# Topic 17: LRU / LFU Cache

"Design an LRU cache" is the most-asked LLD question. A *cache* is a small, fast store that keeps recent answers so you do not recompute them. *LRU* means "least recently used": when the cache is full, drop the entry nobody has touched for the longest time. Most candidates code the textbook HashMap + doubly linked list in 15 minutes. That is only the entry fee. The interview is decided by what comes next:
- `get` is a write, so a read lock is wrong.
- The new key must not evict itself.
- LFU ("least frequently used", drop the entry with the fewest uses) must stay O(1) when keys are removed.
- TTL ("time to live", the age at which an entry goes stale), weight and listeners each have a trap.

A staff answer codes v1 fast, pulls out a policy seam when asked for LFU, and says where Caffeine and Redis do it differently, and why.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [45-Minute Interview Timeline](#5-45-minute-interview-timeline)
6. [Senior vs Staff Rubric](#6-senior-vs-staff-rubric)
7. [Key Takeaways](#7-key-takeaways)

---

## 1. The Problem

### What breaks without one

An unbounded `HashMap` used as a cache grows until the process runs out of memory (OOM). Without a bound, a cache is just a memory leak. The bound forces one decision on every insert: **which entry goes?** The answer is the *policy*, and it decides the hit rate (the share of lookups that find something). The way the policy is built decides whether `get` stays O(1) (constant time) and thread-safe.

### What the interviewer is really probing

| Signal | What they want to see |
|---|---|
| Data structures | O(1) `get`/`put`/evict, and you can say *why* each structure is there |
| Correctness | Evict **before** insert; update ≠ insert; ties defined |
| Concurrency | `get` mutates order → it needs the **write** lock |
| Extensibility | LRU → LFU without rewriting storage or locking |
| Judgement | When LRU loses (scans), when LFU loses (stale popularity) |

### v1 requirements (state these out loud)

- `get(k)` → `Optional<V>`, and counts as an access. `put(k, v)` inserts or updates. `remove(k)`.
- Bounded by entry count (`capacity ≥ 1`). Evict the least recently used entry.
- O(1) per operation. Thread-safe. `null` keys and values are rejected.

### Clarifying questions (with the answers I assume if the interviewer shrugs)

| Question | Assumed answer |
|---|---|
| Does `put` on an existing key count as an access? | Yes, and it reports `REPLACED`. Over an *expired* key it's `EXPIRED` + a fresh insert (LFU count 1) |
| Bound by count or by bytes? | Count in v1, weight in F2 |
| Is `size()` exact under TTL? | No: lazy expiry, so it counts dead entries until `cleanUp()` |
| LFU tie-break? | Least recently used among the minimum frequency (LeetCode 460) |
| Loading on miss (`get(k, loader)`)? | Out of scope; name stampede protection (one load per key) in F3 |

**Out of scope for v1:** loading caches, refresh-after-write, soft/weak references, persistence, distribution.

---

## 2. How It Solves It

### Entities and invariants

| Entity | Invariant |
|---|---|
| `BoundedCache` | `map.size() == policy.size()`; `Σ weight == totalWeight ≤ maxWeight` (`checkInvariants()`) |
| `EvictionPolicy<K>` | Tracks exactly the stored keys; `victim(exclude)` never returns `exclude` |
| `LruPolicy` | Circular DLL, one sentinel: `sentinel.next` = most recent, `sentinel.prev` = victim |
| `LfuPolicy` | Buckets ascend by freq; no bucket is empty; the victim is `root.next`'s least recent key |
| `Removal(key, value, cause)` | Every entry that leaves is reported exactly once |

### Worked example: LRU eviction

Capacity is 3. The list runs from most recent (left) to least recent (right). The right end is the victim, meaning the next entry to go.

| Step | Call | List after | What happens |
|---|---|---|---|
| 1 | `put(a)` | a | Inserted at the front |
| 2 | `put(b)` | b a | Inserted at the front |
| 3 | `put(c)` | c b a | Full now |
| 4 | `get(a)` | a c b | A read moves `a` to the front, so `b` is now the oldest |
| 5 | `put(d)` | d a c | Full, so evict the right end (`b`) first, then insert `d` |

A sentinel (a dummy node that points at itself when the list is empty) means the list never has a null end, so no step needs a null check.

### Public interface first

```java
public interface Cache<K, V> {
    Optional<V> get(K key);      // an access: refreshes recency / bumps frequency
    void put(K key, V value);    // insert or update (REPLACED); may evict (SIZE)
    Optional<V> remove(K key);   // EXPLICIT
    int size();  int cleanUp();  CacheStats stats();
    static <K, V> Cache<K, V> lru(long capacity) { … }
}
Cache<String, byte[]> c = CacheBuilder.<String, byte[]>newBuilder()
        .policy(Policy.LFU).maximumWeight(64 << 20, (k, v) -> v.length)
        .expireAfterWrite(Duration.ofMinutes(5)).removalListener(listener, onError).build();
```

Errors fail fast where the mistake is made:
- `maximumSize(0)` or `expireAfterWrite(≤ 0)` → IAE from that setter.
- Setting both bounds → ISE from the second setter. Setting neither → ISE from `build()`.
- A negative weight → IAE from `put`. `null` → NPE.

### v1: the one-class answer (minutes 10–20)

```java
final class LruCache<K, V> {
    private final class Node { K k; V v; Node prev, next; }
    private final Map<K, Node> map = new HashMap<>();
    private final Node head = new Node();               // sentinel: head.next = MRU, head.prev = LRU
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();   // not a ReadWriteLock: get relinks
    { head.prev = head.next = head; }

    V get(K k) { lock.lock(); try {
        Node n = map.get(k); if (n == null) return null;
        unlink(n); linkFirst(n); return n.v;
    } finally { lock.unlock(); } }

    void put(K k, V v) { lock.lock(); try {
        Node n = map.get(k);
        if (n != null) { n.v = v; unlink(n); linkFirst(n); return; }   // update ≠ insert
        if (map.size() == capacity) { Node lru = head.prev; unlink(lru); map.remove(lru.k); }  // evict first
        n = new Node(); n.k = k; n.v = v; linkFirst(n); map.put(k, n);
    } finally { lock.unlock(); } }
    // unlink / linkFirst: four pointer writes each. The sentinel removes every null check.
}
```

`LinkedHashMap(cap, 0.75f, true)` + `removeEldestEntry` is the 5-line answer. Say it, then say why it isn't thread-safe even behind a `ReadWriteLock` ([Topic 12 M2](../12-common-mistakes/README.md#m2-in-an-lru-get-is-a-write)).

### Class diagram (after the F1 extraction)

```
 Cache<K,V> ◄──── BoundedCache<K,V> ───────────────► EvictionPolicy<K>  (package-private, not thread-safe)
  (public)        │ ReentrantLock                       onInsert / onAccess / onRemove
                  │ HashMap<K, Entry(v, weight, exp)>   victim(exclude) / size
                  │ Weigher, Ticker, RemovalListener        ▲               ▲
 CacheBuilder ────┘ hits/misses/evictions/expirations   LruPolicy        LfuPolicy
  .policy(LRU|LFU) .maximumSize|maximumWeight            DLL + sentinel   bucket list
```

### Sequence: one `put` of a new key

```
caller ─ put(k,v) ─► weigh(k,v)                 caller code, outside the lock
                     lock ─► now = ticker.read()
                             evictWhile(w, null): victim() → detach → Removal(SIZE)   ← BEFORE insert
                             map.put; policy.onInsert(k)
                     unlock
                     dispatch(removals) → listener, each in try/catch → onError     ← AFTER unlock
```

### Worked example: O(1) LFU with frequency buckets

A *bucket* groups all keys with the same use count. Buckets sit in a list, lowest count first. Inside a bucket, the right end is the least recently used. Capacity is 3.

| Step | Call | Buckets after (count: keys) | What happens |
|---|---|---|---|
| 1 | `put(a)`, `put(b)`, `put(c)` | 1: c b a | New keys start at count 1 |
| 2 | `get(a)` | 1: c b, 2: a | `a` moves to the next bucket, created because it did not exist |
| 3 | `get(b)` | 1: c, 2: b a | `b` joins bucket 2 at the front |
| 4 | `put(d)` | 1: d, 2: b a | Full. The victim is the right end of the first bucket (`c`). Evict `c`, then insert `d` |
| 5 | `remove(d)` | 2: b a | Bucket 1 is now empty, so it is unlinked at once. The minimum is again the first bucket |

Every step touches a fixed number of pointers, so it is O(1). The minimum is always the first bucket, so there is no counter to keep in sync.

### The algorithms

| Structure | get | put | remove | evict | Note |
|---|---|---|---|---|---|
| LRU: DLL + sentinel | O(1) relink | O(1) | O(1) | `sentinel.prev` | |
| LFU: bucket list (Shah et al. 2010) | O(1): move to adjacent `f+1` bucket | O(1) | O(1) | `root.next.keys.prev` | Empty buckets unlinked at once |
| LFU: LeetCode `minFreq` counter | O(1) | O(1) | O(1)* | `buckets[minFreq]` | *`minFreq` can go stale, see below |
| Policy seam cost | Exactly 2 hash lookups on a hit (map + policy index) | a few more | a few more | | O(1); constant ~2× (**estimate**, not benchmarked) |

```
LFU buckets (circular, ascending)
 root ◄─► [f=1: d c] ◄─► [f=3: a] ◄─► [f=7: b] ◄─► root
                  ▲ victim: least recent of the first bucket
 get(c): f=2 doesn't exist → create it between f=1 and f=3, move c, keep f=1 (d still in it)
```

**Finding: the `minFreq` trick fails later than the plan said.** The plan said it "goes stale once remove can empty the min bucket". The tests say that's only half true:

| Bound | How the min bucket ends up empty or excluded | Result (test) |
|---|---|---|
| Count | Size < capacity, so the next insert can't evict, and it resets `minFreq=1` first | **Correct**: 100k random ops match the model (`underACountBound…`) |
| Weight | **Simplest case, no remove:** one insert needs two victims. The first empties bucket 1, and the second `victim()` reads it | **No victim** (`…OneInsertNeedingTwoVictims…`) |
| Weight | `remove` empties bucket 1, then one insert needs eviction right away | **No victim, over bound** (`…InsertAfterRemoveFindsNothingToEvict`) |
| Weight | A growing update bumps `minFreq` to its own, now excluded, bucket | **No victim** (`…GrowingUpdateFindsNoVictimButItself`) |

The bucket list has no counter to keep in sync: the minimum is always `root.next`. That holds only while no empty bucket stays linked. `LfuPolicy.checkInvariants()` asserts it after every differential op. It also checks that frequencies are strictly ascending, the back-pointers are right, and node count == index. Deleting the unlink fails 4 test classes.

### Worked example: TTL (expireAfterWrite)

TTL is 10 s. Times are seconds on the injected ticker (a clock that tests can move by hand).

| Time | Call | Result |
|---|---|---|
| 0 | `put(a)` | Expires at 10 |
| 6 | `get(a)` | Hit. A read does not extend the TTL |
| 9.999999999 | `get(a)` | Hit, one nanosecond before the deadline |
| 10 | `get(a)` | Miss. `now - expiresAt >= 0`, so it is removed and reported `EXPIRED` |
| 10 | `size()` | Without this `get`, `size()` would still count `a`: expiry is lazy |

Why subtraction? `nanoTime` can sit near `Long.MAX_VALUE`. Then `now + ttl` wraps to a negative number, and `now >= expiresAt` would call a fresh entry expired. The difference `now - expiresAt` stays correct after a wrap.

### Worked example: why `get` needs a write lock

Two threads call `get` on the LRU list `c b a` at the same time. Both only "read", but each one relinks a node.

| Step | Thread 1: `get(a)` | Thread 2: `get(b)` | Result |
|---|---|---|---|
| 1 | Unlinks `a` | Unlinks `b` | Both are half-way through editing the same neighbours |
| 2 | Links `a` to the front | Links `b` to the front | Pointers overwrite each other |
| 3 | | | A node is lost or a pointer is null. `ConcurrencyTest` with a read lock saw "over bound" and a null-pointer error |

With one lock, step 2 of thread 2 waits until thread 1 is done, so the list stays valid.

### Concurrency model

| Shared state | Invariant | Primitive | Why |
|---|---|---|---|
| map + policy + weight + stats | Map and policy agree; weight ≤ max | One `ReentrantLock` | `get` writes (relink / bump), so a read lock would corrupt the list |
| Ticker read | Per-cache time never runs backwards | Read **inside** the lock | Otherwise a stale `now` can come after a fresher one |
| Listener | Never runs under the lock | Collect `Removal`s, dispatch after `unlock` | A slow or blocking listener otherwise stalls every caller |
| Listener failure | The cache and the other events are unaffected | `try/catch` **per removal** → `onError`; a throwing `onError` is caught too and logged (`System.Logger`) | [Topic 12 M7](../12-common-mistakes/README.md#m7-listener-isolation) |

### Code and tests

| File | What it shows |
|---|---|
| [`BoundedCache`](src/main/java/com/lld/cache/BoundedCache.java) | Lock, evict-before-insert, the weight/TTL rules, dispatch after unlock, `checkInvariants()` (map ≡ policy key sets + the policy's own structure) |
| [`LruPolicy`](src/main/java/com/lld/cache/LruPolicy.java) · [`LfuPolicy`](src/main/java/com/lld/cache/LfuPolicy.java) | The two O(1) policies behind [`EvictionPolicy`](src/main/java/com/lld/cache/EvictionPolicy.java) |
| [`CacheContractTest`](src/test/java/com/lld/cache/CacheContractTest.java) | Same 6 contract tests × both policies, plus build-time validation |
| [`LruBehaviourTest`](src/test/java/com/lld/cache/LruBehaviourTest.java) · [`LfuBehaviourTest`](src/test/java/com/lld/cache/LfuBehaviourTest.java) | Recency refresh; ties; remove empties the min bucket; buckets created and freed |
| [`DifferentialTest`](src/test/java/com/lld/cache/DifferentialTest.java) | 100k seeded ops × 5 seeds × 2 policies vs naive O(n) [`ReferenceModels`](src/test/java/com/lld/cache/ReferenceModels.java), with keys + invariants checked after every op |
| [`MinFreqCounterLfuTest`](src/test/java/com/lld/cache/MinFreqCounterLfuTest.java) | The LeetCode trick vs the bucket list (the finding above) |
| [`PolicyTradeoffTest`](src/test/java/com/lld/cache/PolicyTradeoffTest.java) | A scan flushes LRU's hot set, not LFU's; after a workload shift, LRU gets 297 hits and LFU gets 0 |
| [`ExpiryTest`](src/test/java/com/lld/cache/ExpiryTest.java) | Live at ttl−1 ns, gone at ttl; reads don't extend; overwrite resets; `cleanUp`; nanoTime overflow; an expired victim is `EXPIRED`; a put over an expired key is a fresh insert |
| [`WeightTest`](src/test/java/com/lld/cache/WeightTest.java) | One insert evicts 3; oversized → `SIZE`, not stored, and an oversized *update* drops the old value too; a growing update evicts others, never itself; no overflow near `Long.MAX_VALUE` |
| [`RemovalListenerTest`](src/test/java/com/lld/cache/RemovalListenerTest.java) | Per-event isolation (1 throw among 3 events still delivers 3); a throwing `onError` is contained; lock not held (same-thread probe); cross-thread `get` doesn't deadlock |
| [`ConcurrencyTest`](src/test/java/com/lld/cache/ConcurrencyTest.java) | 8 threads × 50k ops; ready latch + start gate; exact totals, e.g. `puts − REPLACED − SIZE − EXPLICIT == size()` |

Mutation checks: every mutant below passes a weaker test and fails the current one (details in [commands.md](commands.md)).

| Mutant | Killed by |
|---|---|
| No lock in `get` | `ConcurrencyTest`, 3/3 runs |
| `readLock()` in `get` (a `ReadWriteLock`) | `ConcurrencyTest`, 5/5 runs |
| Naive `now >= expiresAt` | `expiryIsCorrectAcrossNanoTimeOverflow` |
| LFU never unlinks empty buckets | `LfuPolicy.checkInvariants` via 4 test classes |
| One try/catch around the whole dispatch loop | `aThrowingListenerDoesNotCostTheOtherEvents…` |

---

## 3. The Gaps

### Follow-up rounds (this is where staff is decided)

**F1: "Now make it LFU."** Delta: extract `EvictionPolicy<K>` (Strategy). Storage, locking, stats and listener stay in `BoundedCache`, and the policy is pure single-threaded bookkeeping.
- The new policy is the bucket list, not the `minFreq` counter (see the finding).
- The cost: each policy keeps its own key → node index. That's exactly 2 lookups per `get` hit (more for `put`/`remove`) and ~2× per-entry overhead (both **estimates**, see below). Caffeine avoids both by putting the policy links inside the storage entry.
- Name the LFU flaw: counts never decay, so yesterday's hot keys squat forever (`PolicyTradeoffTest`).

**F2: "Add TTL, size in bytes, and eviction callbacks."**

| Feature | Rule | Trap |
|---|---|---|
| `expireAfterWrite` | Half-open: expired when `now - expiresAt >= 0` | `now >= expiresAt` breaks when `now + ttl` crosses `Long.MAX_VALUE` (tested) |
| Lazy expiry | Checked on `get`/`put`/`remove`; `cleanUp()` is an O(n) sweep | Dead entries still hold capacity until touched, swept or picked as victim. A dead victim is reported `EXPIRED`, not `SIZE` (Caffeine semantics, tested) |
| Weight | `while (w > max − total)` evict; one insert can evict many | `total + w > max` overflows near `Long.MAX_VALUE` (tested). An entry heavier than `max` would flush everything and still not fit, so refuse it (`SIZE`). On an update that also **drops the old value** (`REPLACED`, then `SIZE`) |
| Growing update | Subtract the old weight, then `victim(exclude = key)` | Without `exclude`, LFU can evict the value it's storing (tested) |
| Listener | Collected under the lock, called after it, one try/catch per event | Called under the lock → deadlock (tested cross-thread). One try around the loop → one throw loses the rest (tested) |

Known gap: TTL is fixed per cache. A fixed TTL makes the expiry order FIFO by write time, so a second write-ordered list would make `cleanUp` O(expired). A variable TTL per entry needs a heap (O(log n)) or a hashed timing wheel (O(1) amortized), as in [Topic 23](../23-delayed-job-scheduler/) and Caffeine's `expireAfter(Expiry)`.

**F3: "Make it fast under 64 threads" / "What does Redis do?"**

| Option | How | Cost |
|---|---|---|
| Lock striping | N independent caches by `hash(k) % N` | Global LRU becomes per-stripe LRU; capacity is per stripe ([Topic 12 M3](../12-common-mistakes/README.md#m3-segmented-lru-is-a-trade-off)) |
| Caffeine buffers | `ConcurrentHashMap` for data; reads go to a **lossy striped ring buffer**, writes to a bounded queue; one thread at a time drains them into the policy (lock amortization) | Policy order is approximate; reads never block |
| W-TinyLFU | An admission window + a main space, sized to the workload; a 4-bit CountMin sketch (~8 B/entry) decides who survives, and TinyLFU halves its counts periodically | Fixes both LRU's scans and LFU's stale counts |
| Redis approximate LRU/LFU | Samples `maxmemory-samples` (default 5) keys and evicts the best of them; LFU uses a Morris counter + decay (`lfu-log-factor 10`, `lfu-decay-time 1`) | No per-key list: one 24-bit field per object (LRU: a clock; LFU: 16-bit last-access minutes + 8-bit log counter) instead of two pointers |

### Common mistakes

| Myth | Reality | Test |
|---|---|---|
| "`get` is a read, use a `ReadWriteLock`" | `get` relinks the list. Two readers corrupt it | `ConcurrencyTest` with `readLock()` in `get`: fails 5/5 (lost stat updates, NPE, over bound) |
| Insert, then evict if over | The new LFU key (freq 1) is the minimum and evicts itself | `aNewKeyIsNeverEvictedByItsOwnInsert` |
| Update = insert | It must be an access + `REPLACED`, with no growth in size | `updateReplacesTheValueWithoutGrowing` |
| "LFU is O(1) with a `minFreq` counter" | Only while every eviction follows an insert; it breaks under weight | `MinFreqCounterLfuTest` |
| LFU beats LRU | Not after a workload shift: 0 vs 297 hits | `afterAWorkloadShift…` |
| LRU is scan-safe | One pass of unique keys flushes the hot set | `aScanFlushes…` |
| `now >= expiresAt` | Wraps at `Long.MAX_VALUE`; use subtraction | `expiryIsCorrectAcrossNanoTimeOverflow` |
| Reads extend `expireAfterWrite` | They don't; that would be `expireAfterAccess` | `readsDoNotExtendTheTtl` |
| Call the listener in place | Under the lock: blocking + deadlock risk; one throw breaks `put` | `RemovalListenerTest` |
| `total + w > max` | Overflows near `Long.MAX_VALUE` and evicts nothing; use `w > max − total` | `…NearLongMaxValueStillEvicts` |
| `size()` is live entries | Under lazy expiry it counts dead ones too (1,001 before `cleanUp`, 1 after) | `cleanUpPurges…` |

### When NOT to hand-roll one

- **Production JVM code:** use Caffeine. It has a better hit rate (W-TinyLFU) and no global lock. Hand-rolling is for the interview, or for a hard no-dependency rule.
- **Values shared across instances:** a per-process cache gives N inconsistent copies. Use Redis/Memcached, or accept the staleness and invalidate explicitly.
- **Whole-dataset fits in memory and never changes:** an immutable map, no eviction at all.

### Production delta

| Concern | This module | Production |
|---|---|---|
| Per-entry overhead | ~140 B LRU / ~150 B LFU before key + value (**estimate**: 2 map nodes + `Entry` + policy node, compressed oops; the v1 one-class version is ~70 B) | Caffeine: policy links inside the entry, + ~8 B/entry sketch; Redis: a 24-bit field per object |
| Throughput | **Not benchmarked**: one lock serializes every op | Lock-free reads via buffers |
| Expiry | Lazy + O(n) sweep | Timing wheel, maintenance on the write path |

---

## 4. Official References

- Shah, Mitra, Matani, *An O(1) algorithm for implementing the LFU cache eviction scheme* (2010): http://dhruvbird.com/lfu.pdf
- Einziger, Friedman, Manes, *TinyLFU: A Highly Efficient Cache Admission Policy*: https://arxiv.org/abs/1512.00727
- Caffeine design (read/write buffers, lock amortization): https://github.com/ben-manes/caffeine/wiki/Design
- Caffeine eviction (size, weight, time) and efficiency (hit-rate traces): https://github.com/ben-manes/caffeine/wiki/Eviction · https://github.com/ben-manes/caffeine/wiki/Efficiency
- Redis key eviction (approximate LRU/LFU, `maxmemory-samples`, Morris counter): https://redis.io/docs/latest/develop/reference/eviction/
- Redis source, `unstable` branch, checked 2026-10-23. `LRU_BITS 24` and `robj.lru`: https://github.com/redis/redis/blob/unstable/src/server.h. The 16 + 8-bit LFU split: https://github.com/redis/redis/blob/unstable/src/evict.c
- `LinkedHashMap` access order + `removeEldestEntry`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/LinkedHashMap.html
- `ReentrantLock`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/ReentrantLock.html
- `System.nanoTime` (compare with `t1 - t0`, because of overflow): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/System.html
- LeetCode 146 (LRU) and 460 (LFU): https://leetcode.com/problems/lru-cache/ · https://leetcode.com/problems/lfu-cache/

---

## 5. 45-Minute Interview Timeline

| Min | Do | Say out loud |
|---|---|---|
| 0–5 | Clarifying questions; v1 requirements | "Does `put` on an existing key count as an access?" |
| 5–10 | `Cache` interface; draw map + DLL + sentinel | "The sentinel removes every null check." |
| 10–20 | Code v1: one class, one lock | "Evict before insert. And `get` writes, so no read lock." |
| 20–27 | Tests: capacity, recency refresh, update ≠ insert | "Next, a model-based test against a naive O(n) list." |
| 27–38 | F1: extract the policy, LFU buckets | "Not the `minFreq` counter. Here's when it breaks." |
| 38–45 | F2/F3 out loud: TTL, weight, listener, striping, Caffeine, Redis | "Caffeine never blocks a read. Redis samples 5 keys." |

If you're behind at minute 20, skip LFU code. Draw the buckets and give the O(1) argument.

## 6. Senior vs Staff Rubric

| Area | Senior (hire) | Staff (strong hire) |
|---|---|---|
| v1 | Correct map + DLL LRU | Also names the `LinkedHashMap` answer and why it isn't thread-safe |
| Correctness | Passes the happy path | Evict-before-insert, update semantics, `exclude` on growth, oversized entries |
| LFU | `minFreq` counter | Bucket list; can say exactly when `minFreq` breaks |
| Concurrency | `synchronized` everything | Says why `get` is a write; listener after unlock; striping vs buffers trade-off |
| Testing | Unit tests | A differential test vs a reference model; a concurrency test with conservation laws |
| Policy judgement | "LFU is better" | Scan vs pollution; W-TinyLFU; Redis sampling |
| Drives | Waits for follow-ups | Raises TTL/weight traps and the policy seam before being asked |

## 7. Key Takeaways

1. HashMap for lookup + a DLL for order. A sentinel node makes every relink four writes and no null checks.
2. **`get` is a write.** One `ReentrantLock`, not a `ReadWriteLock`.
3. **Evict before insert**, or LFU's new key evicts itself.
4. LFU in O(1) = a list of frequency buckets, each one an LRU list. The minimum is always the first bucket.
5. The LeetCode `minFreq` counter is correct only while every eviction follows an insert. It fails under a weight bound.
6. LRU loses to scans, and LFU loses to workload shifts. W-TinyLFU (Caffeine) fixes both.
7. TTL: half-open, overflow-safe subtraction, lazy + sweep. Weight: evict many, refuse the impossible, never evict the key being written.
8. Listeners run after unlock, each one isolated.
9. Prove it: a differential test vs a naive model, plus exact conservation under 8 threads.

---

**Self-grade: STRONG.** It has the full spine, and both policies are O(1) and tested against reference models (1M differential ops, with structural invariants checked after every op). Every TTL/weight/listener trap is pinned by a test, and 5 mutants are killed (the table in §2). The `minFreq` claim was corrected because a test contradicted it. Known gaps: no throughput benchmark (the README says "not benchmarked"), the per-entry memory is an estimate (not measured with JOL), and variable TTL / W-TinyLFU are described, not coded.

[← Topic 16: Elevator](../16-elevator/) · [Index](../README.md) · [Topic 18: Logging Framework →](../18-logging-framework/)
