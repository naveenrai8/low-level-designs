# Topic 26: Connection Pool

"Design a connection pool" sounds like a list of free connections. The interviewer is waiting for the places where that simple answer breaks:

1. Two threads both see "room for one more" and the pool opens more than `max` connections.
2. A caller waits for ever, or a wake-up is lost because the return landed just before the wait began.
3. A bad connection is handed out, or a failed open quietly uses up a slot until the pool can never reach `max` again.

A staff answer names these first, then shows a pool whose one counter is changed only under one lock.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [45-Minute Timeline](#5-45-minute-timeline)
6. [Senior vs Staff Rubric](#6-senior-vs-staff-rubric)
7. [Key Takeaways](#7-key-takeaways)

## 1. The Problem

Opening a database connection is slow (network, login, setup) and the database allows only so many. A *connection pool* keeps a few connections open and lends them out, so callers skip the opening cost and the database never sees more than `max`.

**What is really being probed:** a counter that must never pass `max`, a wait that must end, and cleanup on every failure path.

**v1 requirements**
- `borrow(timeout)` gives a connection, waiting up to `timeout` if all are out.
- `release(c)` gives it back for reuse.
- A connection is checked (*validated*) before it is handed out. A bad one is closed and replaced.

**Clarifying questions (assumed answers)**

| Question | Assumed answer |
|---|---|
| What if nothing frees up in time? | `PoolTimeoutException`. The pool is unchanged. |
| Can the caller be interrupted while waiting? | Yes. `InterruptedException` is thrown, and no slot or waiter count leaks. |
| What if opening a connection fails? | The exception goes to the caller and the slot is given back. |
| Who is served first among waiters? | Whoever the lock wakes. Fair ordering is a trade-off (section 3). |
| Real database? | No. A small `Connection` interface and a fake factory stand in. |

**Out of scope:** real drivers, transactions, prepared-statement caches, dynamic resizing from metrics.

## 2. How It Solves It

**Words used here.** A *slot* is the right to own one live connection. `total` counts slots in use. *Idle* means open and free. A *loan* is a connection that is out. A *handle* is the small wrapper a borrower holds for one loan.

**Invariant:** `total == idle + borrowed + opening + validating + closing` and `total <= max`, always. `min` counts borrowed connections too: it is a floor on `total`, not on idle.

### The interface and its error contract

```java
Connection borrow(Duration timeout) throws InterruptedException; // PoolTimeoutException | ConnectionException | IllegalStateException(closed)
void release(Connection c);      // IllegalStateException: stale handle, stranger, or double return
void invalidate(Connection c);   // same contract; closes instead of reusing
void warmUp();                   // open until min exist
int  evictIdle();                // closes idle > maxIdle, never below min; returns how many
List<Leak> findLeaks();          // loans older than leakThreshold (0 = off)
Stats stats();                   // idle, borrowed, total, waiting
void close();                    // idle closed now, borrowed closed on return
```

In all failure cases of `borrow` no slot is lost.

### Class diagram

```
 ConnectionPool <<interface>>
        ^
        | implements
 BoundedPool --- lock, available (Condition)
   |  |  |
   |  |  +--> total, closing, waiting, closed        (guarded by lock)
   |  +-----> idle : ArrayDeque<Idle>                (newest first)
   +--------> loans : IdentityHashMap<Lease, Loan>   (who, when, stack)
 Lease implements Connection  --wraps-->  raw Connection (from ConnectionFactory)
```

### Borrow flow

```
borrow(timeout)
  |-- lock --> idle has one? ----yes--> take it --unlock--> validate --ok--> lend handle
  |                |no                                           |bad
  |                v                                            v
  |          total < max? --yes--> total++ --unlock--> create   close, total--, signal, check deadline, loop
  |                |no                                  |throws: total--, signal, rethrow
  |                v
  |          deadline passed? --yes--> PoolTimeoutException
  |                |no
  |                +--> awaitNanos(remaining) --> loop (re-check everything)
```

### The borrow timeline (max = 1, timeout = 5 s)

| Time | Thread | What happens | Result |
|---|---|---|---|
| t0 | A | Nothing idle, `total` 0 < 1, so reserve: `total` = 1, release the lock | A is opening |
| t0 | B | Nothing idle, `total` 1 is not < 1, so wait | B is parked, nothing was created twice |
| t1 | A | Opens the connection, lends a handle | A holds it |
| t2 | A | `release`: puts it in idle, signals one waiter | B wakes |
| t2 | B | Takes it from idle, validates it | B holds it |
| t3 | C | Arrives with a 5 s timeout, nothing free, waits | C is parked |
| t8 | C | Deadline passes | `PoolTimeoutException`, nothing changed |

The check "is there room?" and the reservation `total++` are one step under the lock. That is the whole fix for the first gap. Waiting is `awaitNanos(remaining)` in a loop that re-checks the state, so a late wake-up or an early one is harmless. The core of it:

```java
lock.lockInterruptibly();
try {
    while (true) {
        candidate = idle.pollFirst();
        if (candidate != null) break;                       // reuse
        if (total < config.max()) { total++; break; }       // check + reserve in ONE step
        long remaining = deadline - ticker.read();
        if (remaining <= 0) throw new PoolTimeoutException("...");
        waiting++;
        try { available.awaitNanos(remaining); } finally { waiting--; }
    }
} finally { lock.unlock(); }
// slow work (validate or create) happens here, outside the lock
```

**Cost:** `borrow` and `release` are O(1) (a deque pop or push and a map put). `evictIdle` is O(evicted). `findLeaks` is O(borrowed) and holds the lock while it scans.

### Why a handle per loan

If the pool lent the raw connection, this would be accepted: A borrows `c` and returns it, B borrows `c`, A returns `c` again (a bug in A). The pool cannot tell the second return from B's, so `c` goes idle while B still uses it and a third caller shares it. So every loan gets a fresh `Lease` handle (HikariCP does the same with its proxy). Returning switches the handle off: a second return, or any use after return, throws. The test `staleDoubleReturnAfterRelendIsRejected` is exactly this story. A `Semaphore(max)` alone cannot do this, because it knows how many are out but not which.

### Why the slow work is outside the lock

Validating, opening and closing talk to the network. Doing them under the lock would freeze every borrower behind one slow call. So the pool reserves first (cheap, locked), does the slow call (unlocked), and fixes the books after (cheap, locked). While the slow call runs the connection still counts in `total`, which keeps the cap true.

Order matters on the way out too: a connection is **closed first, then its slot is given back**. The other way round, a new connection could open while the old one is still closing, and the live count would pass `max` for a moment. Three tests hold a close half-way and check this for validation, `invalidate` and eviction.

### The failure paths (each gives its slot back)

| Event | What the pool does |
|---|---|
| Idle connection fails validation | Close it, `total--`, signal one waiter, check the deadline, try the next |
| `factory.create()` throws | `total--`, signal one waiter, rethrow |
| Waiter is interrupted | `waiting--` in `finally`, pass on any signal it may have eaten, rethrow |
| `release` of a stranger, a twin or a stale handle | `IllegalStateException`, nothing changes |
| Pool closed while a connection is being opened | The new connection is closed and the borrower is refused |
| Pool closed while a borrower holds a connection | It is closed when it comes back |

The "pass on any signal" line in the interrupt path is defensive. With a `ReentrantLock` an interrupted waiter that also got a signal is hard to produce on demand, so no test covers it; it is there so a wake-up is never wasted.

### Concurrency model

- **Shared state:** `idle`, `loans`, `total`, `closing`, `waiting`, `closed`.
- **Primitive:** one `ReentrantLock` and one `Condition` (`available`). A return signals one waiter. A freed slot also signals one waiter. Both kinds of waiter want the same thing: "try again". `evictIdle` and `close` use `signalAll` because they can free several slots at once.
- **Why one condition is enough:** every waiter has the same wish. With different wishes (readers and writers) you would need two conditions.
- **Loans use an `IdentityHashMap`** keyed by handle, so only the exact object the pool lent is accepted back.
- **Fake time and real parking:** `awaitNanos` parks on the real clock, so moving a fake ticker wakes nobody. Tests move the ticker and then call the test seam `onTimeAdvanced()`, which wakes all waiters so they re-read the ticker. Other seams are `Probe` (pause a thread at a chosen point) and a lock-queue check used to prove a releaser has arrived.
- **What the timeout covers:** the loop checks the deadline before each next idle connection, so K slow bad idle connections cannot add up past it. A single slow `create()` or `isValid()` call is not interrupted (see F3).

Background reading, not repeated here: [ReentrantLock](../../internal-pocs/Java-poc/concurrency/16-reentrantlock/), [Conditions and lost wake-ups](../../internal-pocs/Java-poc/concurrency/17-conditions/), [Semaphore](../../internal-pocs/Java-poc/concurrency/24-semaphore/), [Topic 22: the bounded queue](../22-blocking-queue-thread-pool/) (the same wait-on-full idea), and [Topic 10: virtual time](../10-time-and-testability/) (why the ticker is injected).

### Code and tests

| File | What it is |
|---|---|
| [ConnectionPool.java](src/main/java/com/lld/connpool/ConnectionPool.java) | The interface and its error contract |
| [BoundedPool.java](src/main/java/com/lld/connpool/BoundedPool.java) | The implementation |
| [Connection.java](src/main/java/com/lld/connpool/Connection.java), [ConnectionFactory.java](src/main/java/com/lld/connpool/ConnectionFactory.java) | The fake-able resource |
| [PoolConfig.java](src/main/java/com/lld/connpool/PoolConfig.java), [Leak.java](src/main/java/com/lld/connpool/Leak.java), [Stats.java](src/main/java/com/lld/connpool/Stats.java) | Settings and reports |
| [Probe.java](src/main/java/com/lld/connpool/Probe.java) | Test seams that pause a thread at a chosen point |
| [ConnectionPoolTest.java](src/test/java/com/lld/connpool/ConnectionPoolTest.java) | 32 tests, no sleeps |
| [Fakes.java](src/test/java/com/lld/connpool/Fakes.java) | Manual ticker, fake connection, factory that counts live and peak |

### What each risk test proves

| Risk | Test | How it is forced |
|---|---|---|
| Never more than `max` | `peakNeverExceedsMaxUnderContention` | 24 threads behind a start gate, 200 rounds each, the factory's peak of live connections is at most 3 |
| Check-then-create race | `checkThenCreateRaceCreatesExactlyOne` | A is paused after reserving; B must choose to wait; exactly 1 create |
| Bounded wait | `borrowGivesUpAfterTimeout` | Fake ticker: at 4 s the waiter goes back to waiting, at 5 s it throws |
| One return wakes one | `returnWakesExactlyOneWaiter` | The first woken waiter is frozen inside the lock; with `signalAll` the second would already be queued on the lock, so the test fails |
| No lost wake-up | `returnBetweenCheckAndWaitIsNotLost` | The waiter is held in the gap until the releaser is seen queued on the lock; fails if check and wait were two lock holds |
| Interrupt leaks nothing | `interruptedWaiterLeaksNothing` | Flag restored, `waiting` 0, `total` unchanged |
| Leak report | `leakIsReportedWithBorrowStack` | Stack has the borrowing method, report appears exactly at the threshold |
| Double, stale or foreign return | `doubleAndForeignReturnsAreRejected`, `staleDoubleReturnAfterRelendIsRejected` | Throw, counters unchanged, and the re-lent connection is not shared |
| Close before free | `validateDropFreesSlotOnlyAfterClose`, `invalidate...`, `eviction...` | A close is held half-way on a latch; a second borrower on `max` = 1 must stay parked and the peak stays 1 |
| Timeout with slow checks | `badIdleConnectionsCannotPushBorrowPastDeadline` | Each check burns 2 s of fake time; the 5 s budget ends the borrow |
| Close during open | `closeWhileOpeningRefusesAndLeaksNothing` | live and total end at 0 |
| Bad connection replaced | `badIdleConnectionIsReplaced` | Old one closed, new one opened |
| Failed create | `failedCreateDoesNotLeakASlot`, `failedCreateWakesAWaiter` | After 2 failures the pool still reaches `max` |
| Eviction | `evictionRespectsMinAndBorrowed`, `evictionStopsAtMin`, `evictionBoundaryIsInclusive`, `warmUpOpensExactlyMin` | Never below `min`, never a borrowed one, exactly at the limit goes |
| Everything together | `randomOperationsMatchModel` | 5,000 random steps, counters agree with the factory's live count |

## 3. The Gaps

### F1: Leak detection

A *leak* is a borrower that never returns the connection. The pool slowly runs dry and every caller times out. The pool cannot take it back safely (the borrower may still be using it), so it records evidence.

| Time | What happens | Result |
|---|---|---|
| t0 | Thread `worker-7` borrows | Loan records the time, thread name and a `Throwable` made at that line |
| t29 s | `findLeaks()` | Empty, under the threshold |
| t30 s | `findLeaks()` | One `Leak` with the stack that shows where it was borrowed |

Creating a `Throwable` costs a few microseconds (estimate), so `captureStacks` can be turned off; it is built before taking the lock. A threshold of `0` turns detection off, as in HikariCP. The connection stays out; reporting is the pool's job, fixing the code is yours. In real code return it in a `finally` or with try-with-resources (`Lease.close()` returns it) so it cannot be forgotten.

### F2: Min/max sizing and idle eviction

- `warmUp()` opens `min` connections ahead of the first caller. `max` is the hard cap.
- `evictIdle()` closes connections idle longer than `maxIdle`, **oldest first**, never below `min`, and it only looks at the idle list, so a borrowed connection cannot be closed.
- Borrowing takes the **newest** idle connection. A busy pool keeps reusing the same few and lets the rest age out, so the pool shrinks by itself when load drops.
- Why evict at all: the database or a firewall closes connections that sit silent. A connection that is too old is likely already dead.
- How big should `max` be? Fewer than you think: see the HikariCP "About Pool Sizing" page in the references. (The Java-poc [sizing thread pools](../../internal-pocs/Java-poc/concurrency/32-sizing-pools/) topic is about thread pools, the same arithmetic idea but not connections.)

### F3: Health check and failover (design delta, not coded)

The v1 check runs when a connection leaves the idle list. To add more:
- **Background check:** a scheduler (an injected `Scheduler` seam, as in Topic 10) calls `isValid()` on idle connections every N seconds, using the same close-then-free path as eviction.
- **Max lifetime:** close any connection older than a limit (HikariCP defaults to 30 minutes) when it returns, so a failed-over primary is picked up. Add a `createdAt` to the pooled-entry record `Idle`/a new entry type, not to `Loan`, because it must survive across loans.
- **Failover:** the factory holds a list of hosts. After K failures in a row it moves to the next host and the pool *drains*: existing connections are marked stale and closed as they come back, never in the middle of a query.
- **Fail fast and a slow `create()`:** a black-holed host makes `create()` hang. Our timeout bounds queueing and the gaps between checks, not one stuck call. Chosen: state it and, in production, pass the remaining time to the factory (like JDBC `isValid(seconds)`) and put a circuit breaker in front ([Topic 27](../27-circuit-breaker-and-retry/)) so waiters fail at once.

### Misconceptions and common mistakes

| Mistake | Why it is wrong |
|---|---|
| "A `Semaphore(max)` is the whole pool" | It gates the count, but it does not know which connection is out, so it cannot detect leaks, foreign returns or a stale double return (that needs a handle per loan) |
| `if (idle.isEmpty() && total < max) total++` outside the lock | The check-then-create race |
| Free the slot before the close finishes | The live count briefly passes `max` |
| Forget `signal` on a failed create | A waiter sleeps until its timeout though a slot is free |

### Trade-offs and when not to pool

- **Unfair wake-up:** `ReentrantLock(false)` is faster but a waiter can be overtaken. A fair lock gives order at a throughput cost.
- **One lock:** fine for hundreds of borrowers per second, since the critical sections are tiny (estimate, not measured here; the 24-thread test only proves correctness). At very high rates HikariCP uses a lock-free hand-off structure, see [Down the Rabbit Hole](https://github.com/brettwooldridge/HikariCP/wiki/Down-the-Rabbit-Hole).

### Production delta

- A per-connection reset on return (rollback), separate pools per workload, and `max` times instance count under the database limit.
- Metrics: wait time, usage time, timeouts, pool size, as HikariCP exports to [Dropwizard](https://github.com/brettwooldridge/HikariCP/wiki/Dropwizard-Metrics) and [Micrometer](https://github.com/brettwooldridge/HikariCP/wiki/MicroMeter-Metrics).

## 4. Official References

- [HikariCP README](https://github.com/brettwooldridge/HikariCP): leak detection (0 = off), `maxLifetime` (default 30 minutes).
- [HikariCP: Down the Rabbit Hole](https://github.com/brettwooldridge/HikariCP/wiki/Down-the-Rabbit-Hole): the lock-free `ConcurrentBag` and the connection proxy.
- [HikariCP: About Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing): why a small pool is faster.
- [javax.sql.DataSource (JDK 21)](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/javax/sql/DataSource.html): the standard way to get a connection.
- [Condition (JDK 21)](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/Condition.html): `awaitNanos` and spurious wake-ups.
- Brian Goetz et al., *Java Concurrency in Practice*, chapter 14 (condition queues, lost signals).

## 5. 45-Minute Timeline

| Minutes | Do |
|---|---|
| 0-5 | Clarify: timeout rule, interrupt, create failure, who waits first |
| 5-10 | Say the invariant (`total <= max`) and the three gaps out loud |
| 10-25 | Write `borrow` and `release` with one lock and one condition |
| 25-33 | Failure paths: bad connection, failed create, interrupt |
| 33-40 | Leaks and eviction (F1, F2) |
| 40-45 | F3 and the production delta |

## 6. Senior vs Staff Rubric

| Senior | Staff |
|---|---|
| A queue of free connections plus a timeout | One counter reserved in the same locked step as the check, and proof by a forced race |
| Validates when handing out | Validates outside the lock, and a bad one gives its slot back and wakes a waiter |
| Throws on timeout | Every exit path (timeout, interrupt, failed create) leaves the books exact |
| Mentions leaks | Records the stack at borrow time and rejects double and foreign returns |
| Picks a `max` by feel | Knows a small `max` wins, and that `max` times instances must fit the database |

## 7. Key Takeaways

1. One counter, changed only under one lock, is what keeps `total <= max`.
2. Reserve under the lock, do slow work outside it, fix the books afterwards.
3. Close first, free the slot second.
4. Every failure path must give the slot back **and** wake a waiter.
5. A wait is a loop against a deadline from an injected ticker, so tests never sleep.
6. Test the races by pausing threads at chosen points, not by hoping.

[← Topic 25: Pub-Sub](../25-pub-sub/) · [Index](../README.md) · [Topic 27: Circuit Breaker and Retry →](../27-circuit-breaker-and-retry/)

**Self-grade: STRONG.** Remaining gap: F3 is a design delta only, a single stuck `create()` is not interrupted, fairness is not tested, and the pass-on of a signal in the interrupt path is defensive and untestable with `ReentrantLock`.
