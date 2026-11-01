# Topic 45: Content Popularity

"Show the trending tags" sounds like a `HashMap` and a sort. The interviewer is waiting for what breaks: old counts that never expire, a bucket that comes back to life after a quiet night, memory that grows with every new tag, old news that outranks fresh news, and one loud customer that slows everyone else.

A staff answer counts in small time buckets, wipes a bucket before reusing it, ranks with one fixed tie rule, bounds memory with a sketch plus a small heap, decays old events lazily, and gives every tenant its own state.

## Table of Contents

[0 Words](#0-words-used-in-this-topic) · [1 Problem](#1-the-problem) · [2 How It Solves It](#2-how-it-solves-it) · [3 Gaps](#3-the-gaps) · [4 References](#4-official-references) · [5 Timeline](#5-45-minute-interview-timeline) · [6 Rubric](#6-senior-vs-staff-rubric) · [7 Takeaways](#7-key-takeaways)

---

## 0. Words used in this topic

| Word | Plain meaning |
|---|---|
| **Event** | One sighting of an item, for example "someone viewed article 7". |
| **Item** | A tag or a piece of content that we rank. |
| **Top-K** | The K items with the highest counts, hottest first. |
| **Sliding window** | "Only the last W seconds count." The window moves with the clock. |
| **Bucket** | One slice of the window, B seconds long. We keep one small count map per bucket instead of one entry per event. |
| **Ring** | A fixed array of buckets used over and over. Bucket number n lives in slot n mod (buckets in window). |
| **Stale bucket** | A slot that still holds counts from a bucket one window (or more) ago. It must be wiped before it is reused. |
| **Half-open** | A span that includes its start and excludes its end: `[start, end)`. |
| **Decay** | Old events count for less as time passes. |
| **Half-life** | The time after which an event's weight is cut in half. |
| **Count-Min sketch** | A small fixed table of counters that estimates counts for any number of items. It may guess too high, never too low. |
| **Heavy hitter** | An item that is seen a lot. Top-K is just the K heaviest hitters. |
| **ε (epsilon) and δ (delta)** | The error budget of a sketch: the estimate is at most ε·N too high (N is the total number of events) with probability at least 1 − δ. |
| **Tenant** | One customer of the service. Tenants must not see or affect each other. |
| **Differential test** | Run the same random input through the real code and a slow, obviously right model, then compare. |

Bucket rotation is taught in [Topic 30](../30-sliding-window-metrics/). Here we reuse the idea and only show what top-K adds. Top-K by trie is in [Topic 40](../40-autocomplete-top-k/) and sorted rankings in [Topic 41](../41-leaderboard/).

## 1. The Problem

Build "trending": count events per item, and answer `topK(k)` for the last W seconds.

Questions to ask, with the answers assumed here:
| Question | Assumed answer |
|---|---|
| What does "last W seconds" mean at the edge? | Half-open, at bucket granularity: an event leaves between W−B and W after it happened (up to one bucket early, never late). |
| Late or out-of-order events? | Inside the window: counted in their own bucket. Older than the window: rejected (`recordAt` returns `false`). Later than the clock, even by 1 ns: `IllegalArgumentException`. |
| Ties? | Higher count first, then smaller id. Always the same answer. |
| k = 0, k > distinct items, empty window? | Empty list, all items, empty list. Negative k is an error. |
| Exact or approximate? | v1 exact. F2 approximate with a stated error bound. |
| Single node? | Yes. Distribution is in the production delta. |

Out of scope: persistence, a REST adapter, cross-node merging.
## 2. How It Solves It

### 2.1 Entities, invariants and shape
| Entity | Invariant |
|---|---|
| `WindowSpec` | bucket length > 0, bucket count > 0; live buckets are `cur-buckets+1 .. cur` |
| Ring slot (map or sketch+heap, plus the bucket number it holds) | a slot's contents always belong to the bucket number stored beside it |
| `Ranked` order | count descending, then id ascending, everywhere |
| `CountMinSketch` | every counter is at least the true count of anything hashed to it |
| `IndexedMinHeap` | at most K entries, each id once, root is the weakest |
| `DecayingPopularity` | score = stored × `2^(-(now-ref)/halfLife)`; `now-ref` never exceeds 64 half-lives at a write |
| `MultiTenantPopularity` | one private tracker per tenant; at most `maxTenants` trackers |

```
 record(item) ──> Ticker ──> bucket n = floor(t / B) ──> slot = n mod 6
                                                           │ slot holds a different n? wipe it
                                                           ▼
 ring: [ s0 ][ s1 ][ s2 ][ s3 ][ s4 ][ s5 ]   (exact: map / bounded: CMS + heap)
 topK(k) ──> sum live slots ──> sort by Ranked.ORDER ──> first k
 MultiTenantPopularity: tenantId ──> own TopK (own lock) ──> ring above
```

### 2.2 The interface
```java
public interface TopK {
    void record(String item);                      // event "now"
    boolean recordAt(String item, long atNanos);   // late event; false if older than the window
    List<Ranked> topK(int k);                      // hottest first, ties by id
}
```

Time comes from an injected `Ticker`, so tests move a fake clock and never sleep.

### 2.3 Worked example: bucket rotation with an idle gap
Bucket = 10 s, window = 60 s, so the ring has 6 slots. Bucket number = floor(time / 10). Slot = bucket number mod 6.

| Time | What happens | Result |
|---|---|---|
| t = 5 s | 5 events for `old`. Bucket 0, slot 0. | slot 0 holds bucket 0: `old` = 5 |
| t = 59 s | Bucket 5. Live buckets are 0..5. | `old` = 5 still counts |
| t = 60 s | Bucket 6. Live buckets are 1..6. Bucket 0 is exactly one window behind. | `old` is gone from the answer (half-open edge). So an event leaves between W−B and W after it happened |
| t = 185 s | One event for `new`. Bucket 18, slot 0 (18 mod 6). Slot 0 still says "bucket 0: old = 5". | The write sees the slot's bucket number (0) is not 18, wipes the slot, then counts `new` |
| t = 185 s | `topK(5)` | only `new` = 1 |

The classic bug is skipping the wipe. The slot is reused, the old 5 is added to the new count, and a tag that died long ago is trending again. The read side has a second guard: a slot whose bucket number is outside the live range is skipped even if nobody has written to it since.

Cost: a write is O(1). A read sums at most `buckets` small maps and sorts the distinct items: O(D log D) for D distinct items.

### 2.4 Exact top-K
`WindowedTopK` keeps `HashMap<String, Long>` per slot plus the bucket number each slot holds. `topK` sums the live slots and sorts with one rule (`Ranked.ORDER`: count descending, then id ascending). `k` larger than the item count returns everything.

All public methods are `synchronized` on the tracker (the sketch version and `DecayingPopularity` do the same), and a probe test checks the lock is held. The invariant is "slot content matches slot bucket number", and a write touches both. For deeper locking choices see [Java-poc concurrency](../../internal-pocs/Java-poc/concurrency/).

### 2.5 Worked example: decay and the lazy trick (F1)
Idea: an event is worth `weight × 0.5^(age / halfLife)`. Half-life = 100 s. A single event is worth 1, 0.5, 0.25, 0.125 after 0, 100, 200, 300 s.

The naive way multiplies every score every second. The lazy way never touches old scores. Instead it makes new events bigger: an event at time t is stored as `weight × 2^((t − ref) / halfLife)`, and reading multiplies the stored sum by `2^(−(now − ref) / halfLife)`. Here ref = 0.

| Time | What happens | Stored sum (item `a`) | Meaning |
|---|---|---|---|
| t = 0 | event, weight 1 | 1 × 2^0 = 1 | |
| t = 100 | event, weight 1 | 1 + 1 × 2^1 = 3 | |
| t = 200 | read: factor 2^−2 = 0.25 | 3 × 0.25 = 0.75 | check by hand: first event 0.25, second 0.5, sum 0.75 |

Why it works: every item shares the same read factor, so stored numbers already sort in the right order. A write touches one entry.  The catch is that stored numbers double every half-life. After about 1000 half-lives a double becomes infinity, and infinity times the read factor 0 gives NaN. The fix is renormalisation: when `now − ref` passes 64 half-lives (checked on every write), multiply everything by the read factor once, set `ref = now`, and drop entries that fell below 1e-12. A long quiet gap simply multiplies by something tiny and ends at zero. Weights are limited to `[1e-6, 1e250]`, so a fresh event is never below the prune line and `weight × 2^65` cannot overflow.

The tests simulate 10,000 half-lives of steady events (the score must stay finite and equal 2.0, the sum of 1 + 1/2 + 1/4 + ...) and a gap of a million half-lives (the old score must be exactly 0, not NaN).

### 2.6 Worked example: a Count-Min sketch (F2)
Exact counting needs one entry per distinct item. With millions of tags that is too much memory. A Count-Min sketch uses a fixed grid of `depth` rows and `width` columns. To add an item, each row hashes it to one column (each row with its own hash) and adds 1 there. To ask, take the smallest of the item's counters.

A 3-row by 5-column grid (small on purpose). Pretend the hashes send `a` to columns (1, 4, 2), `b` to (1, 0, 3), and the never-seen `c` to (1, 4, 3). Events: `a`, `b`, `a`.

| Row | col 0 | col 1 | col 2 | col 3 | col 4 |
|---|---|---|---|---|---|
| 0 | 0 | 3 (a, b, a) | 0 | 0 | 0 |
| 1 | 1 (b) | 0 | 0 | 0 | 2 (a, a) |
| 2 | 0 | 0 | 2 (a, a) | 1 (b) | 0 |

| Query | Counters read | Answer | Truth |
|---|---|---|---|
| `a` | row0 col1 = 3, row1 col4 = 2, row2 col2 = 2 | min = 2 | 2 |
| `b` | 3, 1, 1 | min = 1 | 1 |
| `c` | row0 col1 = 3, row1 col4 = 2, row2 col3 = 1 | min = 1 | 0 (too high, never too low) |

Collisions only add, so every counter is at least the truth, so the minimum is at least the truth. That is the one-sided error. Taking the minimum over several rows makes it unlikely that all rows collide badly.

Sizing: `width = ceil(e / ε)` and `depth = ceil(ln(1 / δ))`. For ε = 0.01 and δ = 0.01 that is 272 × 5 counters = 1,360 longs = **10,880 bytes** per sketch, whether it sees 100 items or 100 million.

Hash: the item string is hashed to 64 bits with FNV-1a over its UTF-16 chars (not bytes). Row r uses `splitmix64(hash XOR seed_r) mod width`, where the row seeds come from `SplittableRandom(seed)`. These rows are not a proven pairwise-independent family, so ε and δ here are empirical (backed by the Zipf run below); the caller's seed makes runs repeatable.

**Heap for heavy hitters.** A sketch can tell you the count of an item but cannot list the top items. So we keep a min-heap of size K next to it. After each add, offer the item's new estimate: if the item is already in the heap, update it in place (an index map finds it; no duplicates); else if there is room, add it; else it replaces the root (the weakest) only if it beats it. Ties use the same count-then-id rule, so heap and exact version agree.

**Sketch + sliding window.** A sketch cannot forget one event. So there is one sketch (and one heap) per bucket, in the same ring as v1. Reading takes the union of the live buckets' heap ids and sums each id's estimates over the live buckets. Expiry is free: an old bucket is not read, and it is cleared before reuse (same stale-bucket rule as 2.3). Total memory is `buckets × 10,880` bytes for ε = δ = 0.01, plus at most `buckets × heapSize` heap entries. With the test setting (6 buckets) that is 6 × 5 × 272 × 8 = 65,280 bytes.

**Measured error.** Setup: JDK 21.0.8, Zipf-like stream (item of rank r has weight 1/r), 5,000 items, N = 200,000 events, ε = δ = 0.01 (272 × 5), seed 11, one run (the seed makes it repeatable).

| Measure | Value |
|---|---|
| Bound ε·N | 2,000 |
| Max overestimate | 980 |
| Mean overestimate | 206.1 |
| Items above the bound | 0 of 5,000 |
| Items below the true count | 0 (checked for every item) |

The bound is a worst case: the real mean error is about a tenth of it here.

Known limit, with a test as witness: with a heap of 2, an item `m` seen 2 times in each of 3 buckets (exact total 6) is crowded out of every bucket's heap by two ids seen 3 times, so the exact answer is `m` and the sketch answer misses it. Use a larger heap or shorter buckets.

### 2.7 Multi-tenant isolation (F3)
`MultiTenantPopularity` holds a `ConcurrentHashMap<tenantId, TopK>`. Each tenant has its own tracker, so one tenant's ids cannot appear in another's answer (tested with the same id in two tenants).

Memory policy: the caller's factory should build a fixed-size `SketchWindowedTopK` per tenant (the constructor takes any factory, and the exact version would not be bounded), so a noisy tenant can only fill its own tables. There is no shared pool to starve, and nothing to evict. Total counter memory is at most `maxTenants × per-tenant bytes`, plus heap entries that hold id strings of any length (limit id length at the edge). When the cap is reached a new tenant is refused with `TenantLimitException`; existing tenants are never evicted. A read for an unknown tenant returns an empty list and does not create it, so reads cannot fill the table. A negative `k` throws for every tenant.

Locking: each tenant's tracker has its own lock, so tenants never wait for each other. A single global lock would let one busy tenant stall all. Striping (hash tenants into N locks) saves memory but lets two unrelated tenants collide on a stripe by accident. Creating a new tenant takes one short lock so two racing newcomers cannot both slip past the cap.

### 2.8 Code and tests
| Piece | Files |
|---|---|
| Interface, rows, geometry, exact | `TopK`, `Ranked`, `Scored`, `WindowSpec`, `WindowedTopK` |
| Sketch, heap, bounded window | `CountMinSketch`, `IndexedMinHeap`, `SketchWindowedTopK` |
| Decay, tenants | `DecayingPopularity`, `MultiTenantPopularity`, `TenantLimitException` |
| Tests | `WindowedTopKTest`, `CountMinSketchTest`, `SketchWindowedTopKTest`, `DecayingPopularityTest`, `MultiTenantPopularityTest` |

## 3. The Gaps

**F1 Decay.** Covered in 2.6. Trade-off: decay gives smooth "recent beats old" but there is no hard cut-off, so memory is bounded only by pruning. Combine with a window if you need both.

**F2 Bounded memory.** Covered in 2.5. Trade-off: top-K is approximate, and the answer is the sum of per-bucket overestimates, so the sum of the per-bucket bounds is still ε × N_window (the same absolute bound), but the chance that some bucket breaks it grows (union bound, up to buckets × δ).

**F3 Tenants.** Covered in 2.7.

Common mistakes and myths:

| Myth or mistake | Truth |
|---|---|
| "Store every event and filter by time." | Memory grows with traffic, not with distinct items. Buckets fix that. |
| "A sketch gives the top-K." | It only answers "how many for this item". You need a heap (or another candidate list) to know which items to ask about. |
| "The sketch can undercount." | Never. It can only overcount. |
| "Decay means loop over all scores each second." | Store a growing weight and scale on read. |
| "Just use `Math.pow` forever." | The growth factor reaches infinity. Rebase periodically. |
| "Equal counts can come in any order." | Then two nodes, or two runs, give different answers. Fix a tie rule. |

When not to use this: if the distinct items fit comfortably in memory, use the exact version. 10,880 bytes is roughly what a `HashMap` of a few hundred short strings costs (estimate), so the sketch pays off only when distinct items far outnumber that.

Production delta:

| Concern | Move |
|---|---|
| Many nodes | Each node keeps its own sketches. Sketches add cell by cell, so merge by summing tables (same size and seeds). |
| Persistence and merge | Bucket tables are fixed size, so they store well; sketches of equal size and seeds add cell by cell (Redis `CMS.MERGE` does this). |
| Precision at the head | Keep exact counts for the heap items only. |
| Hot tenant limits | Add per-tenant rate limits ([Topic 21](../21-rate-limiter/)). |

## 4. Official References

| Source | Link |
|---|---|
| Cormode and Muthukrishnan, "An Improved Data Stream Summary: The Count-Min Sketch and its Applications", J. Algorithms 55(1), 2005 (the ε, δ sizing) | https://doi.org/10.1016/j.jalgor.2003.12.001 |
| Count-Min sketch overview | https://en.wikipedia.org/wiki/Count%E2%80%93min_sketch |
| Exponential decay and half-life | https://en.wikipedia.org/wiki/Exponential_decay |
| Redis Top-K: uses HeavyKeeper, which counts with exponential decay (the same idea as F1, applied inside the sketch). Redis-poc has no TopK topic yet | https://redis.io/docs/latest/develop/data-types/probabilistic/top-k/ |
| Redis Count-Min Sketch (`CMS.MERGE`) | https://redis.io/docs/latest/develop/data-types/probabilistic/count-min-sketch/ |
| Kleppmann, Designing Data-Intensive Applications, ch. 11 (stream windows) | book |

## 5. 45-Minute Interview Timeline

| Minutes | Do |
|---|---|
| 0-5 | Ask the edge, late-event, tie and exactness questions from section 1. |
| 5-15 | Interface, ring of buckets, draw the idle-gap timeline. |
| 15-30 | Code the exact version. Say the stale-bucket reset out loud. |
| 30-40 | F1 (lazy decay plus rebase) or F2 (sketch plus heap). |
| 40-45 | Tenants, locking, differential test. |

## 6. Senior vs Staff Rubric

| Area | Senior | Staff |
|---|---|---|
| Window | Buckets and a ring | Wipes stale slots, states the half-open edge, rule for late events |
| Ranking | Sort the map | Fixed tie rule, k edge cases, differential test |
| Scale | "Use Redis" | Sketch with ε, δ sizing, one-sided error, heap, memory in bytes, window via per-bucket sketches |
| Decay | Multiplies all scores | Lazy scaling plus renormalisation, overflow tested |
| Tenants | Prefix the key | Separate state, fixed per-tenant memory, tenant cap, per-tenant lock |

## 7. Key Takeaways

1. Count in buckets; wipe a slot before reuse, and skip out-of-range slots on read.
2. One tie rule everywhere, or answers drift.
3. A Count-Min sketch never undercounts; width = ⌈e/ε⌉, depth = ⌈ln(1/δ)⌉.
4. A sketch needs a heap to list the heavy hitters, and a ring of sketches to forget.
5. Lazy decay stores growing weights; renormalise before they overflow.
6. Isolate tenants by giving each fixed-size private state.

[← Topic 44: Notification Service](../44-notification-service/) · [Index](../README.md) · [Topic 46: API Gateway Core](../46-capstone-api-gateway-core/)

**Self-grade: STRONG.** Remaining gap: single node only (no sketch merge test across nodes), the error was measured on one Zipf-like stream rather than several skews, and the sketch window can miss medium-everywhere items by design.
