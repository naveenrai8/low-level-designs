# Topic 41: Leaderboard

"Build a leaderboard." It looks like a sorted list of scores. The real question is cost: when one score changes among a million users, how fast can you answer "what is my rank?" and "who are the top 10?" without sorting everything again.

A staff answer keeps users in a tree that also counts its subtrees, so rank and top-K are O(log n). It also says out loud how ties are broken, which kind of rank it returns, how daily boards are cut, and how to do the same job with a Redis sorted set.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [45-Minute Timeline](#5-45-minute-timeline)
6. [Senior vs Staff Rubric](#6-senior-vs-staff-rubric)
7. [Key Takeaways](#7-key-takeaways)

## 1. The Problem

What the interviewer is really probing: do you know that a plain sorted list or a `TreeMap` cannot answer "rank of this user" fast. A `TreeMap` has no "how many keys are before this one" call, so you would walk the keys one by one, which is O(n).

**v1 requirements**

- Set or add to a user's score, remove a user.
- Top-K users, rank of one user, users in a rank range (page 3 of the board).

**Clarifying questions, with the answers we assume**

| Question | Assumed answer |
|---|---|
| How many users? | Up to millions, so O(n) per request is not acceptable |
| Two users with the same score? | Earlier to reach it ranks higher, then smaller user id (see 2.2) |
| Which rank numbers? | A unique position (1, 2, 3, 4) plus the shared "1224" rank on request |
| Daily or weekly boards? | Yes, by calendar window in a time zone (F1) |
| Many threads updating? | Yes (F2) |
| Persistence? | Out of scope, the board lives in memory (F3 shows the Redis version) |

**Out of scope:** storage, anti-cheat, friends-only boards, sharding across machines (only described in section 3).

## 2. How It Solves It

### 2.1 Entities and invariants

- A *score entry* is `(score, reachedAtMillis, user)`.
- The *board* is a hash map `user -> entry` plus a tree of entries sorted by the tie rule.
- Invariant 1: the map and the tree hold exactly the same entries, so `size()` is the number of users.
- Invariant 2: the order is total (no two entries compare equal), so every user has one unique position.
- Invariant 3: a score change removes the old entry and inserts the new one in one step. It never edits an entry that sits inside the tree, because that would break the sort order.

### 2.2 The tie rule and the rank meaning

Higher score first. If equal, the user who reached that score earlier. If still equal, the smaller user id.

| User | Score | Reached at | Position (`rank`) | `competitionRank` |
|---|---|---|---|---|
| ann | 100 | 10:00 | 1 | 1 |
| bob | 90 | 10:05 | 2 | 2 |
| cat | 90 | 10:07 | 3 | 2 |
| dan | 80 | 10:01 | 4 | 4 |

- `rank` is the position in the total order: 1, 2, 3, 4. It is what top-K and paging need, because positions never repeat.
- "Dense" ranking (1223) is not offered, it needs a second structure. `competitionRank` is the "1224" rank used by sports tables: equal scores share a number and the next number is skipped. It is 1 plus the number of users with a strictly higher score.
- Re-submitting the same score does nothing and keeps the old time. If it took a new time, re-sending would push the player below everyone they tied with.
- Times are milliseconds in the Java board. Two writes in the same millisecond fall through to the user id. (A monotonic sequence number as the tie-breaker would make same-millisecond ties and clock step-backs fully fair, at the cost of one more counter.)

### 2.3 The interface

```java
public interface Leaderboard {
    void setScore(String user, long score);
    long increment(String user, long delta);       // atomic read-add-write, returns the new score
    boolean remove(String user);
    OptionalLong score(String user);
    OptionalInt rank(String user);                 // unique 1-based position
    OptionalInt competitionRank(String user);      // "1224"
    List<Ranked> top(int k);                       // rows carry rank, user, score, reachedAtMillis
    List<Ranked> range(int fromRank, int toRank);  // 1-based, inclusive, clipped to the board size
    int size();
}
```

Error contract: a blank user, negative `k` or a bad range throws `IllegalArgumentException`. An `increment` that would overflow a `long` throws `ArithmeticException` and changes nothing. An unknown user is not an error, you get an empty optional.

### 2.4 The data structure: a tree that counts

First a small example. Five entries sorted best to worst, and the tree stores "how many nodes are in my subtree" in each node.

| Step | What happens | Result |
|---|---|---|
| Ask for the rank of `C` | Start at the root `D`, `C` sorts before `D`, so go left | Count so far 0 |
| At `B` | `C` sorts after `B`, so `B` and everything left of `B` (1 node) come before `C` | Count 0 + 1 + 1 = 2 |
| At `C` | Found it | Position = count + 1 = 3 |
```
        D (size 5)
       /          \
   B (size 3)      E (size 1)
   /      \
 A (1)    C (1)
```

The rule: while walking down, every time you go right you add `size(left) + 1` to the count. You touch one node per level, so the cost is the tree height.

A *subtree* is a node plus everything below it. An *order-statistic* tree is a search tree that stores subtree sizes so it can answer position questions. Ours is a *treap*: a binary search tree where each node also gets a random priority, and a parent always has a higher priority than its children (that is *heap order*). Random priorities keep the shape balanced on average. It uses `split` and `merge` instead of rotations.

- `rank(user)`: look up the entry in the map, then `countLess(entry) + 1`. O(log n).
- `setScore`: map lookup, `remove(old)`, `insert(new)`. O(log n).
- `top(k)` and `range`: walk by position and skip whole subtrees outside the range. O(log n + k).

**Measured.** Setup: JDK 21.0.8, Apple silicon laptop, `ComplexityTest`, seeded random scores, average over 200 random users, counting comparator calls (not timing, so it never flakes). The naive column is analytic: a linear scan compares every entry.

| Users (n) | Comparisons per `rank()` (tree) | Naive scan | Growth when n grows 10x |
|---|---|---|---|
| 1,000 | 12.9 | 1,000 | |
| 10,000 | 17.3 | 10,000 | about +4.4 |
| 100,000 | 21.7 | 100,000 | about +4.4 |

n grew 100 times and the cost grew 1.7 times, which is the log curve (each 10x adds a constant). An update (remove plus insert) at n = 100,000 costs 41.9 comparisons on average. `top(10)` needs 0 comparisons because it walks by position. The deepest node at n = 50,000 is 41 levels, against log2 n = 15.6 (measured). For a random search tree the expected maximum depth is about 4.31 ln n (Devroye), which is 46 here.

### 2.5 Concurrency model (F2)

- **Shared state:** the map and the tree. They must change together.
- **Primitive:** `ConcurrentLeaderboard` wraps any `Leaderboard` with one `ReentrantReadWriteLock`. Writes (`setScore`, `increment`, `remove`) take the write lock. Reads (`rank`, `top`, `range`) take the read lock and can run side by side.
- **Why one lock and not striped locks:** a rank query needs the order of all users, so the data cannot be cut into independent per-user pieces. A write holds the lock only for one O(log n) tree edit (estimate: a few microseconds).
- **Trade-off:** writers queue, so write throughput does not grow with cores. Past that, the options are a single writer thread fed by a queue (it can batch updates), or sharding users across several boards and merging their top-K lists. For locks in depth see `../../internal-pocs/Java-poc/concurrency/16-reentrantlock` and `18-readwrite-and-stampedlock`.

### 2.6 Code and tests

Files in `src/main/java/com/lld/leaderboard/`: `Leaderboard` and `Ranked` (interface and row), `OrderStatisticTree` (the treap), `SortedLeaderboard` (map plus tree), `ConcurrentLeaderboard` (lock wrapper), `Window`, `WindowedLeaderboard` and `StaleWindowException` (F1), `ZsetCodec` (F3).

| Test | What it proves |
|---|---|
| `LeaderboardBehaviourTest` | Ties, 1224 rank, update moves the entry, range, errors |
| `DifferentialTest` | 20 seeds x 1,500 random ops against `NaiveBoard`, every step compared |
| `ComplexityTest` | Comparison counts at three sizes, and node-visit bounds for rank, top-K and range |
| `WindowedLeaderboardTest` | Boundaries, zones, 23 and 25 hour days, Monday, calendar expiry, stale writes |
| `LockDisciplineTest` | Every wrapper method forwards exactly and holds the right lock; a reader blocks behind a frozen writer |
| `ConcurrencyTest` | 8 threads behind a start gate, exact totals, readers assert invariants mid-run, windowed board too |
| `ZsetCodecTest` | Round trip, limits, the real Redis tie order |

Run and mutation results are in [commands.md](commands.md).

## 3. The Gaps

### F1: ties and time-windowed boards

Ties are already in v1 (2.2). For "today's board" or "this week's board" we keep one board per window.

| Time (New York) | Event | Result |
|---|---|---|
| Mon 23:59:59.999 | `a` scores 10 | Goes to Monday's board |
| Tue 00:00:00.000 | `a` scores 5 | Exactly on the boundary, so it goes to Tuesday's board |
| Tue 09:00 | Ask for Monday's top | `a` has 10 (Monday is kept while within `retain`) |

- A window is **half-open**: it includes its start and excludes its end, so no instant is in two windows or in none.
- Boundaries are **local midnights in the board's zone**. A day on a daylight-saving change is 23 or 25 hours long (both tested). Weekly windows start Monday.
- **Expiry is by calendar:** with `retain = 3` the kept windows are the newest calendar window and the two before it, even if some had no writes. Older windows are dropped and invisible to reads.
- **Clock steps:** a write older than the oldest kept window throws `StaleWindowException` and changes nothing. Without this, one forward clock jump would make every later write be stored and instantly evicted while still returning a score.

### F2: concurrent updates at a high rate

The lock design and its trade-off are in 2.5, the tests in 2.6.

### F3: mapping to a Redis sorted set (ZSET)

A *sorted set* is a Redis type where each member has a double score and members stay sorted. It does with a skip list (with span counts) what our treap does. For the type itself see [`../../pocs/Redis-poc/01-core-data-structures/05-sorted-sets/`](../../pocs/Redis-poc/01-core-data-structures/05-sorted-sets/).

| Our call | Redis command |
|---|---|
| `setScore(u, s)` | `ZADD board s u` |
| `increment(u, d)` | `ZINCRBY board d u` (only while the score has no packed time, see below) |
| `rank(u)` (1-based, best first) | `ZREVRANK board u` plus 1, or `ZRANK` plus 1 with the negated encoding below |
| `top(k)` | `ZRANGE board 0 k-1 REV WITHSCORES` (`ZREVRANGE` works but is deprecated since 6.2) |
| `range(a, b)` | `ZRANGE board a-1 b-1 REV WITHSCORES` |
| `remove(u)` / `size()` | `ZREM board u` / `ZCARD board` |

- **How a ZSET breaks ties:** members with the same score are ordered lexicographically (by bytes, like dictionary order) by member name. In a **descending** read the whole order is reversed, so equal scores come out in reverse name order: `bob` before `abe`. That is the opposite of our "smaller id first".
- **To match our tie rule exactly:** store the negated packed score (`ZsetCodec.encodeAscending`) and read ascending with `ZRANGE board 0 k-1 WITHSCORES` and `ZRANK`. Biggest packed value sorts first, and equal values sort by name ascending. Or accept reverse-id order for users tied in the same second.
- **Packing time into the score:** a double holds integers exactly only up to 2^53, so `ZsetCodec` uses 29 bits for the score and 24 bits for `MAX - secondsIntoSeason`. Flipping the time makes the earlier time give the larger number.

| Score | Second | Packed |
|---|---|---|
| 10 | 5 | 10 x 2^24 + (2^24 - 1 - 5) = 184,549,370 |
| 10 | 6 | 184,549,369 (one smaller, so it ranks below) |
| 11 | 99 | 11 x 2^24 + (2^24 - 1 - 99) = 201,326,492, bigger than both |

- **Codec limits:** score 0 to 536,870,911 (the board accepts negative scores, the codec rejects them), season up to about 194 days. Outside that `encode` throws. The codec counts seconds, the Java board counts milliseconds, so same-second ties in Redis fall back to name order.
- **Updating a packed score:** plain `ZINCRBY d` would corrupt the time bits. `ZINCRBY d*2^24` keeps the old time, which is unfair. A plain `ZADD` of the same score with a new time pushes the user down and breaks "re-submit is a no-op". `ZADD GT` keeps that rule but blocks decreases. The correct way is a Lua script that reads the member, decodes, adds, re-encodes with now (only when the score changed) and writes, all as one atomic step. Without packing, `ZINCRBY` alone is fine.
- **Windows in Redis:** one key per window (`board:2026-03-02`) with `EXPIRE`.

### Misconceptions and common mistakes

- "A `TreeMap` gives rank." It gives order, not position. Counting the keys before one is O(n).
- "Update the score inside the tree entry." The entry's position depends on its score, so remove it first and insert again. Skipping the remove leaves a duplicate (a test proves it).
- "Two users with rank 2 is a bug." It is the 1224 rank. Say which one you mean.
- "Read the score, then write it back." Two threads lose an update. Do it inside one atomic call.

### Trade-offs and when not to use this

- A treap is simple and O(log n), but it has no durability and one lock. Past one machine use Redis ZSETs, or shard by user and merge top-K.

### Production and distributed delta

- Global top-K over shards: take each shard's top-K and merge. This is exact, because the global top-K lies inside the union of the shard top-Ks.
- A user's exact global rank over shards needs, from each shard, the count of entries that sort before mine (higher score, or the same score with an earlier time or smaller id), then a sum.
## 4. Official References

- Redis sorted sets: https://redis.io/docs/latest/develop/data-types/sorted-sets/
- ZADD (GT, LT, INCR): https://redis.io/docs/latest/commands/zadd/
- ZINCRBY: https://redis.io/docs/latest/commands/zincrby/
- ZRANGE (REV): https://redis.io/docs/latest/commands/zrange/
- ZREVRANGE (tie order, deprecated): https://redis.io/docs/latest/commands/zrevrange/
- ZREVRANK: https://redis.io/docs/latest/commands/zrevrank/
- ZREM: https://redis.io/docs/latest/commands/zrem/
- Treap: Aragon and Seidel, "Randomized Search Trees" (1996). Expected depth of random search trees: Devroye (1986).
- Order-statistic trees: CLRS, Introduction to Algorithms, "Augmenting Data Structures" (chapter 14 in the 3rd edition, chapter 17 in the 4th).
- Locks in depth: [`../../internal-pocs/Java-poc/concurrency/16-reentrantlock/`](../../internal-pocs/Java-poc/concurrency/16-reentrantlock/) and [`18-readwrite-and-stampedlock`](../../internal-pocs/Java-poc/concurrency/18-readwrite-and-stampedlock/).
- Related: [Topic 10 time and testability](../10-time-and-testability/), [Topic 08](../08-multi-object-atomicity/), [Topic 21](../21-rate-limiter/).

## 5. 45-Minute Timeline

| Minutes | What you do |
|---|---|
| 0 to 10 | Clarify (users, tie rule, which rank, windows, threads). State the cost problem: a sorted list or `TreeMap` makes rank O(n) |
| 10 to 15 | Draw the counting tree, walk the rank example |
| 15 to 25 | Code the interface, the order, `setScore` as remove then insert, `rank`, `top` |
| 25 to 32 | Tie rule, 1224 vs unique rank, test against a naive model |
| 32 to 38 | Concurrency: atomic `increment`, one read-write lock and its trade-off |
| 38 to 45 | Windows, the Redis mapping, production (shards and top-K merge) |

## 6. Senior vs Staff Rubric

| Area | Senior | Staff |
|---|---|---|
| Rank | Sorted list, or `TreeMap` plus scan | Order-statistic structure, O(log n), cost measured |
| Ties | Not mentioned | A written total order; unique vs 1224 rank named |
| Update | Overwrites the score | Remove then insert, differential-tested |
| Windows | `now() / 86400` | Half-open, zone-aware, calendar expiry, stale-write policy, injected clock |
| Concurrency | `synchronized` everywhere | Atomic increment, lock choice, why striping fails, next step named |
| Redis | "Use a ZSET" | Command mapping, reverse-name tie order in descending reads, time packed in 53 bits, Lua for updates |

## 7. Key Takeaways

- Rank needs a tree that counts its subtrees. Without it, rank is O(n).
- Write the tie rule down and make the order total. Say whether you return a unique position or the "1224" rank.
- A score change is remove-then-insert, never edit in place.
- Windows are half-open, cut at local midnight, expire by calendar, and refuse writes into the past.
- In Redis, descending reads reverse the name order of ties. Pack time into the score only with a Lua script for updates.

[← Topic 40: Autocomplete Top-K](../40-autocomplete-top-k/) · [Index](../README.md) · [Topic 42: Workflow Engine](../42-workflow-engine/)

**Self-grade: STRONG.** Remaining gap: the Redis mapping and the Lua update are described, not run against a live Redis; no multi-shard merge code; no dense (1223) rank; ties use milliseconds, not a sequence number.
