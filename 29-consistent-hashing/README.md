# Topic 29: Consistent Hashing

"Spread keys over N cache or storage servers" sounds easy: `hash(key) % N`. The interviewer is waiting for the moment you add a server:

1. With `hash % N`, going from N to N+1 servers changes the owner of almost every key, so a cache loses nearly all its data at once.
2. A better scheme moves only the keys the new server should take (about 1/(N+1) of them).
3. But a plain ring spreads load unevenly, big and small servers need different shares, and many threads read the ring while operators change it.

A staff answer starts from a small example, shows the movement property and measures it, explains virtual nodes with numbers, and knows when rendezvous or jump hash is the better tool.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [45-Minute Interview Timeline](#5-45-minute-interview-timeline)
6. [Senior vs Staff Rubric](#6-senior-vs-staff-rubric)
7. [Key Takeaways](#7-key-takeaways)

Words not defined inline: a **snapshot** is an immutable picture of the whole membership (readers keep one while writers build the next); a **preference list** is the first N different servers for a key, used for replication; **weight** says how big a server is (weight 2 should get twice the keys of weight 1).

## 1. The Problem

### What breaks with `hash % N`

Ten servers, key hash 1234. `1234 % 10 = 4`, so server 4. Add an eleventh: `1234 % 11 = 2`, so server 2. The key moved, and so do about 10 of every 11 keys (measured below: 91%). For a cache that is a stampede on the database; for storage it is a huge data copy.

### v1 requirements (assumed answers)

| Question | Assumed answer |
|---|---|
| What is placed? | String keys onto named servers. |
| Movement? | Add a server: keys move only to it. Remove a server: only its keys move. |
| Balance? | Within a few percent, controlled by a vnode count. |
| Big and small servers? | Yes, a weight per server. |
| Threads? | Many readers, rare writers. Readers never block. |
| Replicas? | Optionally, the next N different servers. |

Out of scope: moving the data (rebalancing streams), failure detection, gossip, bounded-load variants, and jump hash code.

## 2. How It Solves It

### Worked example first: a 0 to 99 ring

Three servers sit at fixed positions: A at 20, B at 50, C at 80. Five keys have hashed to positions 10, 25, 48, 60 and 95. Rule: walk up from the key's position to the first server at or after it; past 99 wrap to 0. A virtual node (vnode) is one of many positions a single server takes on the ring; this example uses one each.

| Key position | Walk | Owner |
|---|---|---|
| 10 | first server at or after 10 is A (20) | A |
| 25 | next is B (50) | B |
| 48 | next is B (50) | B |
| 60 | next is C (80) | C |
| 95 | nothing after 95, wrap to the start, A (20) | A |

Now add server D at position 40. Only the stretch just below D changes hands (positions 21 to 40, which used to run on to B).

| Key position | New owner | Moved? |
|---|---|---|
| 10 | A | no |
| 25 | **D** (40 is now the first server at or after 25) | yes, B to D |
| 48 | B | no |
| 60 | C | no |
| 95 | A | no |

One key moved, and it moved to the new server. Remove D again and key 25 goes back to B. This is the movement property: **adding a node moves keys only TO the new node (never between two old nodes), and removing a node moves only that node's keys.** Both are tested for every one of 100,000 keys, for the ring and for rendezvous, and also for a weight change: raising a weight moves keys only to that node, lowering it only from that node (`MovementTest`).

### Measured: how many keys move

Setup: 100,000 keys `key-0..key-99999`, seed 7, ring with 200 vnodes per node, one run, JDK 21.0.8 on Apple Silicon. Fraction of keys whose owner changed going from n to n+1 nodes (`MovementTest.movedFractionMatchesTheory` asserts the ring and rendezvous are within 20% of ideal).

| n to n+1 | Ideal 1/(n+1) | Ring | Rendezvous | `hash mod n` |
|---|---|---|---|---|
| 2 to 3 | 0.3333 | 0.3266 | 0.3356 | 0.6664 |
| 5 to 6 | 0.1667 | 0.1778 | 0.1667 | 0.8324 |
| 10 to 11 | 0.0909 | 0.0872 | 0.0908 | 0.9098 |
| 20 to 21 | 0.0476 | 0.0469 | 0.0482 | 0.9513 |
| 50 to 51 | 0.0196 | 0.0215 | 0.0198 | 0.9804 |

Why 1/(n+1): the new node should own its fair share, 1 of n+1 equal shares, and only that share moves. Why `mod` moves n/(n+1): a key stays only when `h % n == h % (n+1)`, about 1 key in n+1.

### Hashing: why not `String.hashCode`

`Hash64` is MurmurHash3 x64 128 (first 64 bits), seeded, written from scratch. `String.hashCode` is deterministic, but it is only 32 bits and similar strings give clustered values, which piles keys onto a few arcs. Checked against published vectors (`Hash64Test`): empty input; "The quick brown fox jumps over the lazy dog" is `e34bbc7bbc071b6c7a433ca9c49a9347`; and seven Guava `Murmur3Hash128Test` vectors with seeds 0 to 5 and tails of 4 to 9 bytes. The 128-bit value is h1 then h2, each printed as big-endian hex; Guava's `HashCode.toString()` and Python `mmh3` show other byte orders, so compare carefully. My own vectors for 1, 16 and 29 bytes were cross-checked by an independent port. The seed is a `long` here; seeds outside [0, 2^31) are not interoperable with the reference (uint32) or Guava (int). Never change seed or algorithm in a deployed system, or every key moves.

### Virtual nodes: why one point per server is not enough

With one point per server the arcs between points have random lengths, so some servers get far more keys. Many points per server average that out. Setup: 10 nodes, 200,000 keys, seed 5, one run (`DistributionTest`). "std-dev/mean" is the standard deviation of keys per node over the average (0 is perfect); "max/mean" is the busiest node versus average.

| Vnodes per node | std-dev / mean | max / mean |
|---|---|---|
| 1 | 0.8121 | 2.776 |
| 10 | 0.2240 | 1.432 |
| 100 | 0.1077 | 1.110 |
| 1000 | 0.0369 | 1.060 |
| rendezvous (no vnodes) | 0.0034 | 1.004 |

Roughly the spread falls like 1/sqrt(vnodes). The price is memory and build time (see the cap below).

### The lookup and the ring rules

```
hashes[]: sorted long[]   owners[]: String[] (same index)

locate(key):
  h = hash(key)
  i = first index with hashes[i] >= h      // binary search, O(log P) for P points
  if i == length: i = 0                    // wrap past the last point
  return owners[i]
```

- Positions are signed longs compared with `Long.compare`, so the ring runs from `Long.MIN_VALUE` to `Long.MAX_VALUE`. A key that hashes exactly onto a point belongs to that point (`exactPointHitBelongsToThatPoint`).
- **Collisions:** two vnodes can land on the same position. Points sort by position, then node name, then vnode number, so the smaller name wins on every machine. Both stay in the ring, so removing the winner hands the spot to the other without moving any other key (`collidingPointsBreakTieByNodeName`, `collisionLoserTakesOverWhenWinnerLeaves`). Members come from a name-sorted map, so this comparator is belt and braces; `tieBreakIgnoresMapIterationOrder` feeds `build` a reverse-sorted map to prove the comparator itself does the work.
- **Size cap:** a node of weight w gets `w x vnodesPerWeight` points. The count uses `long` arithmetic, and the whole ring is capped at 10,000,000 points (`DEFAULT_MAX_POINTS`, about 120 to 160 MB of arrays, an estimate). An add past the cap, or one whose product overflows an int, throws `IllegalArgumentException` and publishes nothing. This was a real bug: `20_000_000 x 200` wrapped negative, so the node was listed but owned zero points (`RingCapTest`).

### Weighted nodes

Vnode count is proportional to weight. Setup: weights 1, 2, 5; 200 vnodes per weight unit; 200,000 keys; seed 21; tolerance 10% relative (`shareFollowsWeight`).

| Node | Weight share | Ring measured | Rendezvous measured |
|---|---|---|---|
| light (1) | 0.1250 | 0.1258 | 0.1238 |
| mid (2) | 0.2500 | 0.2537 | 0.2517 |
| heavy (5) | 0.6250 | 0.6205 | 0.6245 |

### Rendezvous (HRW) hashing, the second implementation

Both classes sit behind the same `Locator` interface, so most tests run on both. Every server scores the key and the highest score wins; adding a server only steals keys it now scores highest on, and removing one only re-picks its own keys. The score is `weight / -ln(u)`, where u is a number strictly inside (0,1) made from a 52-bit slice of the mixed hash (with 53 bits the top value rounds to exactly 1.0 and the score becomes minus infinity; the mix is asymmetric so a key equal to a node's name can still pick that node).

| | Ring with vnodes | Rendezvous |
|---|---|---|
| Lookup cost | O(log P) | O(n) |
| State | sorted array of P points, rebuilt on change | just the list of nodes |
| Balance | depends on vnodes | even by construction (std-dev/mean 0.0034 at 10 nodes) |
| Replica list | walk clockwise, skip repeats | take the top N scores |
| Good for | large n, hot read path | small n (tens), simplicity |

Both have minimal movement (table above).

### Jump hash (discussion only)

Lamping and Veach's jump consistent hash maps a 64-bit key to a bucket number 0 to n-1 in O(log n) time with no memory and a near-perfect spread. The constraint: it can only grow or shrink at the END (10 to 9 buckets always removes bucket 9, whose keys spread over 0 to 8). It cannot express removing a middle bucket: to retire the server behind bucket 3, you must put a different server behind number 3 and move that bucket's data. Fine for numbered shards replaced in place, wrong for caches where any server may die.

### Thread safety: copy-on-write snapshots

```
 Lookup  <--  Locator  <--  AbstractLocator  <--+-- ConsistentHashRing   (sorted long[] + String[])
 locate()     add/remove     AtomicReference     +-- RendezvousLocator    (names, hashes, weights)
 nodes()      snapshot()     <State{members, table}>

 writer: copy members -> build new table -> beforePublish hook -> CAS(old, new) -> lost the race? retry from the winner's state
 reader: state.get() -> use the immutable table          (no lock, no waiting)
```

- **Invariant:** a reader sees the whole old state or the whole new one. Tearing is impossible by construction, because the members and the table live in one immutable `State` swapped with one `compareAndSet`.
- **What the tests prove:** `readerNeverSeesHalfPublishedRing` freezes a writer after it built the new ring but before publishing; a reader on another thread sees exactly the old nodes, then exactly the new ones after release. So unpublished writer work is invisible. `racingWritersLoseNoUpdate` freezes writer one, lets writer two finish, then releases one; both nodes end up present. Latches force the order; there is no sleep. See [atomics and CAS](../../internal-pocs/Java-poc/concurrency/19-atomics-and-cas/).

### The interface

```java
public interface Lookup {
    String locate(String key);                   // throws NoNodesException when empty
    List<String> locate(String key, int count);  // distinct nodes, best first
    Set<String> nodes();
}
public interface Locator extends Lookup {
    void addNode(String node, int weight);       // weight < 1, null or empty name: IllegalArgumentException
    boolean removeNode(String node);             // false if absent; null: IllegalArgumentException
    Lookup snapshot();                           // immutable view
}
```

### Code and tests (48 tests, about 2 s for the module)

| File | What it shows |
|---|---|
| `Hash64` / `Hash64Test` | MurmurHash3 x64 128, published and Guava vectors, avalanche |
| `ConsistentHashRing` / `RingLookupTest`, `RingCapTest` | Binary search; differential test vs a brute-force scan (60 seeded shapes x 300 keys); wrap-around, exact hit, collisions; overflow and cap |
| `RendezvousLocator` / `RendezvousScoreTest` | Weighted HRW; score finite and ordered at the extremes |
| `AbstractLocator` / `SnapshotTest` | Copy-on-write publish, frozen writer and reader, racing writers |
| `LocatorContractTest` | Error contract on both; whole preference list vs an independent oracle |
| `MovementTest`, `DistributionTest` | Movement property, re-weight, and the measured tables |

## 3. The Gaps

- **F1 Weights:** covered above. Changing a weight is a re-add; keys move only to or from that node.
- **F2 Rendezvous and jump hash:** covered above. Tens of nodes and simplicity: rendezvous. Thousands of nodes or a very hot path: ring. Numbered shards that only grow or shrink at the end: jump hash.
- **F3 Replication (coded, small):** `locate(key, N)` collects the next N different physical nodes, skipping vnodes of a node already chosen. This is the Dynamo preference list (section 4.3). The pitfall is adjacent vnodes of one node: naive "next N points" would put three replicas on one machine. `replicationListIsDistinctEvenWithAdjacentVnodes` rigs node `a` to own three neighbouring points and still gets `[a, b, c]`; `wholePreferenceListMatchesOracle` checks the whole list against a brute-force oracle for both implementations. In production also skip nodes in the same rack or zone, a one-line filter in the same walk.

### Misconceptions and common mistakes

- "Consistent hashing balances load." No, it minimizes movement. Balance needs vnodes, and one hot key stays hot.
- "Redis Cluster uses a consistent-hash ring." It does not: it has 16384 fixed hash slots, `CRC16(key) mod 16384` (and only the text inside `{...}` if the key has a hash tag), assigned to nodes and moved explicitly. See the Redis cluster spec and `../../pocs/Redis-poc/09-high-availability/03-cluster/`.
- Using `String.hashCode()`, or changing the hash seed or function in a deployed system.
- Forgetting the data must be copied: the ring says where keys should live, it moves no bytes.
- Mutating the ring in place while readers use it. Build a new table and swap it.

### Production and distributed delta

- Every client must hold the same membership and seed, or two clients route one key to two servers. Membership is usually shared by gossip or a coordinator.
- Rebalance by streaming only the moved ranges; keep the old owner serving until the new one is warm.
- Memory: about 12 to 16 bytes per point, so 100,000 points is about 1.2 MB steady state (estimate); the build peak is higher because of the sort.
- Dynamo section 6.2 compares three token strategies (random tokens, equal-sized partitions with random tokens, equal-sized partitions with fixed tokens) for the same balance-versus-state trade-off.
- Cassandra moved the default `num_tokens` from 256 to 16 in 4.0 (CASSANDRA-13701), together with `allocate_tokens_for_local_replication_factor`; the token allocator is what makes 16 balance well. Fewer tokens cut repair and streaming cost.

## 4. Official References

- [Karger et al., Consistent Hashing and Random Trees (STOC 1997)](https://www.cs.princeton.edu/courses/archive/fall09/cos518/papers/chash.pdf) (the original paper; ACM page: doi 10.1145/258533.258660)
- [Thaler and Ravishankar, A Name-Based Mapping Scheme for Rendezvous (CSE-TR-316-96)](https://www.eecs.umich.edu/techreports/cse/96/CSE-TR-316-96.pdf)
- [DeCandia et al., Dynamo: Amazon's Highly Available Key-value Store (SOSP 2007)](https://www.allthingsdistributed.com/files/amazon-dynamo-sosp2007.pdf) (4.2 partitioning, 4.3 replication, 6.2 token strategies)
- [Lamping and Veach, A Fast, Minimal Memory, Consistent Hash Algorithm (2014)](https://arxiv.org/abs/1406.2294)
- [Redis Cluster specification](https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/) (16384 hash slots, hash tags)
- [MurmurHash3 reference source (smhasher)](https://github.com/aappleby/smhasher/blob/master/src/MurmurHash3.cpp)
- [Apache Cassandra: Dynamo-style architecture](https://cassandra.apache.org/doc/latest/cassandra/architecture/dynamo.html) and [CASSANDRA-13701](https://issues.apache.org/jira/browse/CASSANDRA-13701) (new `num_tokens` default of 16)
- In this repo: [Cassandra vnodes](../../pocs/Cassandra-poc/36-vnodes/), [Cassandra partitioning and tokens](../../pocs/Cassandra-poc/06-partitioning-tokens/), [SystemDesign partitioning schemes](../../pocs/SystemDesign-poc/41-partitioning-schemes/), [Redis Cluster](../../pocs/Redis-poc/09-high-availability/03-cluster/)
- Book: *Designing Data-Intensive Applications*, 1st edition, ch. 6 (partitioning, rebalancing)

## 5. 45-Minute Interview Timeline

| Time | What you do |
|---|---|
| 0-5 | Ask: cache or storage, how many nodes, do they differ in size, replication. Say what is out of scope. |
| 5-10 | Show `hash % N` moving 91% of keys. Draw a small ring and add a node (the worked example). |
| 10-20 | Write `locate`: sorted array, binary search, wrap-around, exact-hit rule. Name the hash and why not `hashCode`. |
| 20-28 | Vnodes: show the spread numbers, choose a count, say the memory cost. Weights as vnode counts. |
| 28-35 | Add/remove: the movement invariant and how you would test it. Preference list with distinct nodes. |
| 35-40 | Concurrency: immutable snapshot plus one atomic reference. |
| 40-45 | Alternatives: rendezvous (O(n), no state), jump hash (end-only), Redis slots. Production: membership agreement and data streaming. |

## 6. Senior vs Staff Rubric

| Signal | Senior | Staff |
|---|---|---|
| Core idea | Draws a ring | States both movement invariants and measures 1/(n+1) vs n/(n+1) |
| Balance | "Add vnodes" | Quantifies spread vs vnode count, knows the 1/sqrt law and the memory cost, caps the point count |
| Hashing | `hashCode` or "some hash" | Seeded 64-bit hash with published vectors, never changed after deploy |
| Edge cases | Wrap-around | Exact-hit boundary, tie-break for colliding points, empty ring, int overflow of the vnode count |
| Replication | Next N points | Next N distinct physical nodes, adjacent-vnode pitfall, rack-aware filter |
| Concurrency | `synchronized` | One immutable State swapped by CAS (no tearing by construction), unpublished work shown invisible by a forced interleaving |
| Alternatives | Knows only the ring | Rendezvous vs ring vs jump vs Redis slots, with the constraint of each |
| Tests | A few examples | Differential test vs brute force, exhaustive movement check, mutation-checked, no sleeps |

## 7. Key Takeaways

1. `hash % N` moves about n/(n+1) of the keys when a node is added; the ring moves about 1/(n+1), and only TO the new node.
2. A key belongs to the first point at or after its hash, with wrap-around (binary search, O(log P)). Balance comes from vnodes (std-dev/mean 0.81 at 1 vnode, 0.04 at 1000); weight is a vnode count, so check overflow and cap the total.
3. Use a seeded, specified 64-bit hash and never change it; break position ties by name so all machines agree.
4. Rendezvous is the stateless alternative (O(n), even spread); jump hash needs end-only changes; Redis Cluster uses fixed slots, not a ring. Publish membership as an immutable snapshot with one atomic swap.

**Self-grade: STRONG.** Full spine, measured movement, spread and weight tables, differential lookup test, published and Guava hash vectors, forced-interleaving snapshot tests, mutation-checked fixes, both algorithms and replication coded. Gaps: jump hash is discussion only, measurements are single-seed runs, and rack-aware placement and real data streaming are design-only.

[← Topic 28: ID Generator](../28-id-generator/) · [Index](../README.md) · [Topic 30: Sliding-Window Metrics →](../30-sliding-window-metrics/)
