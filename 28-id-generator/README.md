# Topic 28: ID Generator

"Design an ID generator" sounds easy: use a counter. The interviewer is waiting for what breaks when there are many servers and no shared counter:

1. Two servers must never issue the same ID, and nobody wants to ask a central database for every ID.
2. IDs should roughly sort by time, so database indexes stay fast.
3. Server clocks go backwards sometimes, and a clock that goes back can repeat an old ID.

A staff answer shows the bit layout, says what happens when the clock steps back, says what happens when a millisecond runs out of numbers, and does the arithmetic for when the design expires.

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
| **Snowflake ID** | A 64-bit number made of three parts: time, machine number, counter. Twitter invented the layout. |
| **Epoch** | The date we call "time zero" for our timestamp. We store milliseconds since the epoch, not since 1970, to make the number small. |
| **Node** | One machine (or process) that issues IDs. Each node has its own number. |
| **Sequence** | A counter that restarts every millisecond. It tells apart IDs made in the same millisecond on the same node. |
| **Clock step-back** | The wall clock jumps to an earlier time, for example after a time-sync correction or a leap-second fix. |
| **UUIDv7 / ULID** | 128-bit IDs that start with a timestamp, so their text sorts by creation time. Written as text, not as a number. |

---

## 1. The Problem

### What breaks without one

An auto-increment column works on one database. With many writers you either funnel every insert through one counter (a bottleneck and a single point of failure), or you use random UUIDv4. Random IDs are unique, but they land all over a B-tree index, so inserts get slow, and sorting by ID tells you nothing about time.

### v1 requirements (assumed answers)

| Question | Assumed answer |
|---|---|
| Size? | 64-bit positive `long`, so it fits a database `BIGINT`. |
| Ordering? | Strictly increasing per generator; roughly time-ordered across nodes (not strictly). |
| Rate? | Up to 4096 IDs per millisecond per node. |
| Nodes? | Up to 1024, each with a unique number. |

### Out of scope

A central ID service over HTTP (see [Topic 13](../13-lld-to-hld-seams/) for the seam), database sequences, and strict global ordering across nodes (that needs one coordinator, see Gaps).

---

## 2. How It Solves It

### The layout

```
 bit 63   62 ................ 22   21 ......... 12   11 .......... 0
 +----+--------------------------+----------------+------------------+
 | 0  |   41-bit timestamp       |  10-bit node   |  12-bit sequence |
 +----+--------------------------+----------------+------------------+
 sign    ms since our epoch         0..1023          0..4095
 (kept 0)
```

Time is the highest field, so a later millisecond always gives a bigger number, and the IDs sort by time. Code: `SnowflakeLayout` (`compose`, `timestamp`, `node`, `sequence`).

### When does it run out? (the arithmetic)

```
41 bits  ->  2^41 = 2,199,023,255,552 milliseconds
          /  (1000 ms x 3600 s x 24 h x 365.25 d) = 31,557,600,000 ms per year
          =  about 69.7 years after the epoch
```

If our epoch is 2024-01-01, IDs stop working in about 2093. The generator throws once the clock passes that point, instead of wrapping around to small numbers (test `timestampExhaustionRejected`). Choosing the epoch near launch day, not 1970, is what buys the years.

Sequence: 12 bits gives 4096 IDs per millisecond per node, which is about 4.1 million per second per node.

### Timeline: normal case and sequence overflow

| Clock (ms) | Call | State before | What happens | ID (time, seq) |
|---|---|---|---|---|
| 1000 | 1 | none | new millisecond, sequence starts at 0 | (1000, 0) |
| 1000 | 2 | (1000, 0) | same millisecond, sequence + 1 | (1000, 1) |
| 1000 | 4096 | (1000, 4094) | sequence + 1 | (1000, 4095) |
| 1000 | 4097 | (1000, 4095) | sequence is used up, so **wait**: call the waiter, re-read the clock | none yet |
| 1001 | 4097 again | (1000, 4095) | the clock moved, new millisecond, sequence 0 | (1001, 0) |

The generator never reuses a sequence and never "borrows" 1001 while the clock still says 1000. Waiting goes through the `Waiter` seam (`SnowflakeIdGenerator`), so tests use a fake waiter that moves a fake clock (`sequenceOverflowWaitsForNextMillisecond`, and `generatorKeepsWaitingUntilClockActuallyMoves` which proves it keeps waiting until the clock really moves).

### The concurrency model

- **Shared state:** one `AtomicLong` holding `(lastTimestamp << 12) | lastSequence`.
- **Invariant:** the packed value only goes up, and every ID comes from a value that was published exactly once.
- **Primitive:** read the state, compute the next one, `compareAndSet`. If another thread won, re-read and try again. No lock, no waiting between threads.
- **Why packing matters:** with two separate fields (`lastMillis` and `sequence`) a thread could read a new time with an old sequence. One `long` makes the pair change in a single step.

The window between "compute next" and "publish" is exactly where a bug would hide. A test hook (`beforeCas`) and a `CyclicBarrier` hold two threads inside that window with the same view of the state. Exactly one CAS wins, the loser retries, and the two IDs differ (`forcedRaceOnStateProducesDistinctIds`). If you replace the CAS with a plain `set`, this test fails (replace the CAS with a plain `set` and this test fails, along with both many-thread tests). The many-thread tests release 16 threads together with a start gate and check exactly 16 x 20,000 distinct IDs.

For why CAS and not a lock, see [atomics and CAS](../../internal-pocs/Java-poc/concurrency/19-atomics-and-cas/). For injected clocks, see [Topic 10](../10-time-and-testability/). For IDs as idempotency keys, see [Topic 11](../11-money-ids-idempotency/).

### The interface

```java
public interface IdGenerator { long nextId(); }          // strictly increasing; throws ClockMovedBackwardsException
public interface StringIdGenerator { String next(); }    // strictly increasing as text
public interface Waiter { void pause(); }                // the "wait for the clock" seam
```

### Code and tests

| File | What it shows |
|---|---|
| `SnowflakeLayout` / `SnowflakeLayoutTest` | Bit positions, sign bit, round trip over 10,000 seeded triples, the 69.7-year arithmetic |
| `SnowflakeIdGenerator` / `SnowflakeIdGeneratorTest` | Ordering, overflow wait, step-back boundary, forced CAS race, exact totals under 16 threads |
| `UuidV7Generator` / `UuidV7GeneratorTest` | RFC 9562 example vector, version and variant, counter overflow, text order |
| `UlidGenerator` / `UlidGeneratorTest` | Spec example vector, "+1" monotonic rule, 64-bit carry, 80-bit overflow |
| `InMemoryNodeRegistry` / `NodeRegistryTest` | F3 seam: unique node numbers, reuse after release, exhaustion |
| `FakeClock`, `GatedRandom` | Test clock that moves by hand, and a random source that freezes one thread inside a generator to force an interleaving |
| `ThroughputBenchmarkTest` | Opt-in benchmark (`-Didgen.bench=true`), off by default |

Interfaces: `IdGenerator`, `StringIdGenerator`, `Waiter`, `NodeIdAssigner`. All generators take an injected `Clock` and `Waiter`.

---

## 3. The Gaps

### F1: Clock step-back

The clock says 998 but we already issued an ID for 1000. If we trust the clock we could repeat an ID.

| Clock now | Last issued | Tolerance 5 ms | What happens |
|---|---|---|---|
| 1000 | (1000, 3) | | normal, (1000, 4) |
| 995 | (1000, 4) | 5 ms behind | **absorbed**: keep using timestamp 1000, sequence 5 |
| 994 | (1000, 5) | 6 ms behind | **throw** `ClockMovedBackwardsException` |
| 1002 | (1000, 5) | recovered | normal, (1002, 0) |
| 997 | (1000, 4095) | separate case: tolerance 5, clock 997 | sequence used up while the clock is still behind: **wait** (997, 998, ... 1001) then (1001, 0) |


**Policy:** a step back of at most `maxBackwardsMillis` is absorbed by staying on the last timestamp and keeping the sequence counting, so IDs still strictly increase. A bigger step throws a typed exception. The reason is liveness, not uniqueness: waiting out a long step back would hold callers (and, for UUIDv7, a lock) for as long as the step, so the tolerance bounds the worst-case stall. When the sequence runs out during an absorbed step back, the generator waits for the real clock to pass the last timestamp and never jumps ahead (`absorbedStepBackThenOverflowWaitsForRealClock`). The boundary is tested exactly: 5 ms behind passes, 6 ms behind throws (`stepBackBoundaryIsExact`).

UUIDv7 and ULID use the same tolerance-and-throw rule (RFC 9562 section 6.2, "Monotonic Error Checking"). Their worst-case stall is the tolerance plus 1 ms.

Other policies: sleep until the clock catches up (fine for a few ms, bad for seconds), or keep a hybrid logical clock. Throwing is the honest choice for a library; the caller can retry or fail over to another node.

### F2: Sortable string IDs (UUIDv7 and ULID)

When you need 128 bits (no node numbers to manage) or a text ID, use a time-prefixed one.

```
UUIDv7 (RFC 9562 section 5.7):
 | 48-bit unix_ts_ms | ver 0111 | 12-bit counter | var 10 | 62 random bits |
 xxxxxxxx-xxxx-7ccc-vxxx-xxxxxxxxxxxx      (hex, 36 characters)

ULID: | 48-bit unix ms | 80 random bits |   (26 Crockford base32 characters)
```

**Which monotonic method?** UUIDv7 here uses RFC 9562 section 6.2 **Method 1**: a fixed-length counter in the 12 `rand_a` bits. It restarts at a random value below 2048 each new millisecond (the RFC's roll-over guard) and adds 1 within a millisecond. If it hits 4095 it waits via `Waiter`. ULID uses the spec's own rule: in the same millisecond add 1 to the previous 80-bit random part; on overflow, wait. Both keep the last millisecond when the clock steps back.

Text order equals creation order because the timestamp comes first and hex or Crockford digits are in ascending character order. Tests check 20,000 IDs across several counter overflows. `java.util.UUID.version()` returns 7 and `variant()` returns 2 (the "10" bits) on every output. A seeded `Random` makes the tests exact; use `SecureRandom` in production.

| | Snowflake | UUIDv7 | ULID |
|---|---|---|---|
| Size | 64 bits | 128 bits | 128 bits |
| Needs node numbers | yes | no | no |
| Unique by | node + sequence | random bits (collision chance is tiny) | random bits |
| Fits `BIGINT` | yes | no (use `uuid` type) | no |

### F3: Node-ID assignment (coded, small)

Two live processes with the same node number can issue the same ID. `NodeIdAssigner.acquire(owner)` returns a number 0 to 1023, the same number for the same owner (so a retried start-up is safe), and throws when all are taken. `InMemoryNodeRegistry` is the in-process version.

**Reuse hazard:** if a released number were given out at once, a new generator on the same millisecond could repeat the old holder's IDs. `InMemoryNodeRegistry` quarantines a released number until the clock is past release time + the step-back tolerance + 1 ms (boundary tested), and a released owner forgets its number. The `owner` must be a unique instance token (a random UUID made at start-up), not a hostname.

**Production delta:** keep the table in etcd, ZooKeeper, or a database row with a **lease** (the owner must renew it every few seconds). A crashed node's number is freed only after the lease expires, and the new owner must wait out the old lease plus the maximum clock error before issuing IDs. A cheap alternative is the pod ordinal of a [StatefulSet](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/), but ordinals are unique only within one StatefulSet in one cluster. Never derive the node from a hash of the IP: with 1024 slots, random picks collide with 50% chance at about 38 nodes and 99% at about 100 (birthday arithmetic: 1 - prod(1 - k/1024)).

### Misconceptions and common mistakes

- "Snowflake IDs are globally ordered." No. They are ordered per node. Across nodes they are ordered only to the accuracy of the clocks (usually milliseconds).
- "`synchronized` is fine." It is correct, but every thread waits. CAS is simple here because the whole state is one `long`.
- "On overflow, just increment the timestamp." That borrows the future: the real clock later reaches that millisecond and you issue duplicates.
- "UUIDv7 needs no coordination, so it never collides." The collision chance is small, not zero, and the counter only keeps order inside one generator instance.
- Using `Thread.sleep(1)` to wait: it sleeps 1 ms or more, wasting the rest of the millisecond. The wait is under 1 ms, so spin.
- Exposing sequential IDs publicly leaks volume and creation time. Use a separate public ID or encode.

### Trade-offs and when not to use

Use a database sequence if there is one writer. Use UUIDv4 if ordering and index locality do not matter. Use Snowflake when you need a compact `BIGINT` and can manage node numbers. Use UUIDv7 when you cannot.

### Production and distributed delta

Clock sync (NTP) must be monitored: alert on offset above your tolerance. Batch allocation (a service hands each node a block of 1000 numbers) avoids the clock entirely but loses time ordering. [Sonyflake](https://github.com/sony/sonyflake) trades the bits differently (10 ms time unit, 8-bit sequence, 16-bit machine id). For strict global order, use a single leader or a timestamp oracle.

---

## 4. Official References

- [RFC 9562: Universally Unique IDentifiers (UUIDs)](https://www.rfc-editor.org/rfc/rfc9562) (section 5.7 UUIDv7, section 6.2 monotonicity, appendix A.6 example)
- [Twitter Snowflake (archived repository README)](https://github.com/twitter-archive/snowflake/tree/snowflake-2010) (the original 41/10/12 layout)
- [ULID specification](https://github.com/ulid/spec) (encoding and monotonic rule)
- [Sonyflake](https://github.com/sony/sonyflake) (a variant layout)
- [Kubernetes StatefulSet](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/) (stable pod ordinals)
- [`java.util.UUID` Javadoc](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/UUID.html) (`version()`, `variant()`)
- Book: *Designing Data-Intensive Applications*, 1st edition, ch. 8 (unreliable clocks) and ch. 9 (ordering guarantees)

---

## 5. 45-Minute Interview Timeline

| Time | What you do |
|---|---|
| 0-5 | Ask size, rate, node count, ordering needs. Say out of scope. |
| 5-10 | Draw the 1 + 41 + 10 + 12 layout. Do the 69.7-year arithmetic out loud. |
| 10-20 | Write `IdGenerator` and the packed-state CAS loop. |
| 20-28 | Overflow: wait for the next millisecond. Say why borrowing is wrong. |
| 28-35 | Clock step-back policy and its tolerance. |
| 35-40 | Node-ID assignment with leases. |
| 40-45 | UUIDv7/ULID as the 128-bit alternative. Tests: fake clock, forced race. |

---

## 6. Senior vs Staff Rubric

| Signal | Senior | Staff |
|---|---|---|
| Layout | Time + node + counter | Exact bits, sign bit, epoch choice, lifetime math |
| Concurrency | `synchronized` | Single packed CAS, race forced by test |
| Clock | Mentions NTP | Policy with tolerance, boundary tested, recovery |
| Overflow | Ignores | Waits through an injected seam, never borrows |
| Node IDs | Hard-coded | Lease-based assignment, startup wait after a crash |
| Alternatives | Knows UUID | Compares UUIDv7 and ULID, names the RFC method |
| Tests | Happy path | Fake clock and waiter, a forced race, exact boundaries, no sleeps |

---

## 7. Key Takeaways

1. Layout: 1 sign bit + 41 time + 10 node + 12 sequence. Time first, so IDs sort by time. 41 bits last about 69.7 years from your epoch.
2. Keep `(timestamp, sequence)` in one atomic `long` and update it with CAS.
3. Sequence overflow: wait for the clock through a seam. Never reuse, never borrow.
4. Clock step-back: absorb a small step, throw on a big one (the tolerance bounds the stall), test the exact boundary.
5. Node numbers are the hard distributed part: lease them, quarantine released ones, never derive them from the IP. UUIDv7 and ULID avoid node numbers.

---

**Self-grade: STRONG.** Full spine, forced races, exact boundaries and spec vectors. Gaps: lease-based node assignment is design-only, and cross-node ordering under real clock skew is not simulated.

[← Topic 27: Circuit Breaker and Retry](../27-circuit-breaker-and-retry/) · [Index](../README.md) · [Topic 29: Consistent Hashing →](../29-consistent-hashing/)
