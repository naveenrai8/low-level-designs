# Topic 10: Time and Testability: Clock, Ticker, Virtual-Time Schedulers

Half the problems in an LLD loop have a TTL, a timeout, a window or an expiry: rate limiters,
seat holds, caches, sessions, circuit breakers, job schedulers. The code that calls
`Instant.now()` and the test that calls `Thread.sleep(…)` are the two habits that sink them. The
sleep makes the test slow and flaky. Worse, it **can never land exactly on the boundary**, which is
where the bugs are. The staff move is to make time a dependency: inject "now", inject "later", and
pick the right kind of clock for each question.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [Key Takeaways](#5-key-takeaways)

---

## 1. The Problem

Three time bugs that pass every `sleep`-based test. All three are in this module, each with a test
that reproduces it in microseconds:

| Bug | Code | What actually happens |
|---|---|---|
| **Inclusive expiry** | `!now.isAfter(expiresAt)` | At exactly `expiresAt` the old holder still blocks the seat. The UI says it's free, and the next user is turned away. A sleep-based test overshoots the boundary by milliseconds, so it never sees this |
| **Stale timer** | expiry task does `holds.remove(seat)` | Alice cancels early and Bob takes the seat. Alice's timer then fires on schedule and **deletes Bob's hold**, so the seat Bob is paying for gets resold |
| **Wall-clock timeout** | `deadline = clock.instant().plus(timeout)` | NTP steps the clock back an hour, and a 10 s timeout waits an hour. If it steps forward, the timeout fires after 1 ms |

The other cost is speed. A 10-minute hold tested with `sleep` takes 10 minutes. Tests like that
get shortened to "1 second TTL, sleep 1.1 s", and then they flake on a loaded CI box.

---

## 2. How It Solves It

### Two questions, two time sources

| Question | Source | Type here | JDK basis |
|---|---|---|---|
| "**What time is it?**" (stamp an expiry, show a countdown, audit log) | wall clock, can jump | `java.time.Clock` (prod `Clock.systemUTC()`, test [`MutableClock`](src/main/java/com/lld/time/MutableClock.java)) | `Clock` / `InstantSource` |
| "**How long has it been?**" (timeouts, rates, latency) | monotonic, never jumps | [`Ticker`](src/main/java/com/lld/time/Ticker.java) (prod `System::nanoTime`, test [`ManualTicker`](src/main/java/com/lld/time/ManualTicker.java)) | `System.nanoTime()` |
| "**Run this later**" (expiry sweep, retry, heartbeat) | scheduler | [`Scheduler`](src/main/java/com/lld/time/Scheduler.java) (prod [`ExecutorScheduler`](src/main/java/com/lld/time/ExecutorScheduler.java), test [`ManualScheduler`](src/main/java/com/lld/time/ManualScheduler.java)) | `ScheduledExecutorService` |

Every domain class takes these through its constructor. Nothing in the domain calls
`Instant.now()`, `System.currentTimeMillis()` or `new ScheduledThreadPoolExecutor`.

### Rule 1: half-open intervals

A hold is valid over **`[createdAt, expiresAt)`**. At exactly `expiresAt` it is gone, and the next
hold can start at that same instant with no gap and no overlap. It's the same convention as
`List.subList` and `String.substring`, and every sliding window in [Topic 21](../21-rate-limiter/).

```java
protected boolean isActive(Hold hold, Instant now) {
    return now.isBefore(hold.expiresAt());           // half-open. The bug: !now.isAfter(...)
}
```

The test pins the clock **one nanosecond before** and **exactly at** the boundary. Only an
injected clock can do that.

### Rule 2: the read path decides; timers only clean up

```
tryHold("7A")    ──► compute(seat): existing active?  ──► keep it / create new hold
                                  (expiry checked HERE, against clock.instant())
scheduler (T+ttl) ──► holds.remove(seat, thatHold)   ← memory cleanup only
```

- Expiry is checked on every read, so a late timer (GC pause, overloaded executor, restart lost
  it) can never make an expired hold look active.
- The timer removes **only the hold it was created for**: `remove(key, value)`, not `remove(key)`.
  That's the stale-timer fix. Cancelling the timer on `release` is a nice-to-have, but it doesn't
  replace the guard, because cancel can race with the timer firing.
- `scheduler.schedule(…)` is called **outside** `compute`, so there's no foreign work under the
  map's bin lock ([Topic 07](../07-concurrency-primitives/)).

### Rule 3: timeouts use the monotonic source

```java
public boolean hasExpired() {
    return ticker.read() - startNanos >= timeoutNanos;   // compare differences, never raw values
}
```

`nanoTime` can start anywhere, including near `Long.MAX_VALUE`. The subtraction is still correct
when it wraps, and a test proves it. Comparing raw values (`now > deadlineNanos`) is wrong, and
the JDK Javadoc says so explicitly.

### Virtual time: `ManualScheduler`

`ManualScheduler.advance(10 minutes)` moves a `MutableClock` forward and runs every task that
falls due, in due order (ties FIFO), on the calling thread. While each task runs,
`clock.instant()` equals **its** due time. Tasks it schedules also run if they fall due inside the
window. Ten virtual minutes take microseconds and give the same result every time.

### Code and tests

| File | Role |
|---|---|
| [`ExpiringHoldRegistry`](src/main/java/com/lld/time/ExpiringHoldRegistry.java) | Fix: injected `Clock` + `Scheduler`, half-open expiry, read path decides, `remove(key, value)` timer |
| [`InclusiveExpiryHoldRegistry`](src/main/java/com/lld/time/InclusiveExpiryHoldRegistry.java) | Bug 1: closed interval (overrides one method) |
| [`StaleTimerHoldRegistry`](src/main/java/com/lld/time/StaleTimerHoldRegistry.java) | Bug 2: timer removes by key (overrides one method) |
| [`WallClockDeadline`](src/main/java/com/lld/time/WallClockDeadline.java) → [`MonotonicDeadline`](src/main/java/com/lld/time/MonotonicDeadline.java) | Bug 3 → fix |

| Test class | What it pins down |
|---|---|
| [`HoldRegistryTest`](src/test/java/com/lld/time/HoldRegistryTest.java) | boundary at `expiresAt - 1ns` and exactly `expiresAt`; inclusive-expiry bug; stale-timer bug and fix; 1,000 holds swept to 0 after one `advance(ttl)`; 32 concurrent users and exactly 1 winner |
| [`DeadlineTest`](src/test/java/com/lld/time/DeadlineTest.java) | wall clock stepped back 1 h means no timeout after 32 s; stepped forward means it fires after 1 ms; monotonic deadline unaffected by either; correct across `nanoTime` overflow |
| [`SchedulerTest`](src/test/java/com/lld/time/SchedulerTest.java) | due order + FIFO ties; each task sees its own due time; self-rescheduling sweep; cancel; one real `ExecutorScheduler` smoke test |

All 16 tests run in about 0.1 s while simulating 10-minute TTLs and hour-long clock steps
(measured on an Apple-silicon laptop). Run them: [`commands.md`](commands.md).

---

## 3. The Gaps

### Misconceptions

- **"`Instant.now()` is fine, I'll mock it."** Static calls can only be mocked with bytecode tricks
  (Mockito's `mockStatic`), and those mocks only apply on the thread that created them. Inject a `Clock` and the problem goes
  away. Java 17+ also has `InstantSource`, a smaller interface for code that never needs a time
  zone. `Clock` implements it.
- **"`Clock.fixed` is enough."** It's immutable, so you can't advance it mid-scenario.
  `Clock.offset(base, d)` creates a new clock per step and the code under test still holds the
  old one. A shared mutable fake is about 40 lines and worth it.
- **"`currentTimeMillis` for timeouts is fine on servers with NTP."** NTP *slews* small errors,
  but it **steps** large ones. VM live-migration, snapshot resume and leap-second handling also
  jump the wall clock. Elapsed time needs `nanoTime`.
- **"The expiry timer is what makes the hold expire."** Then a lost or late timer means a hold
  that lives forever. The timer is garbage collection, and correctness lives on the read path.
- **"Cancelling the old timer fixes stale timers."** Cancel and fire can race, since `cancel`
  returns `false` when the task is already running. Make the timer's action idempotent and
  identity-checked instead.
- **"`awaitility` / polling with a timeout is the deterministic version."** It's *less flaky*
  than a fixed sleep, but it's still real time and still can't hit an exact boundary. Use it for
  true integration tests (a real `ExecutorScheduler`), and virtual time for logic.

### Trade-offs

| Choice | Pro | Con |
|---|---|---|
| Lazy expiry on read only | no threads, trivially correct | memory never shrinks for keys that are never read again |
| Timer per entry (this module) | prompt cleanup | one scheduled task per hold. At 1M holds that's 1M heap entries in the executor's queue |
| Periodic sweep (one task every N s) | O(1) scheduled tasks | O(n) scan per sweep; cleanup lag up to N s |
| Hashed timing wheel (Netty `HashedWheelTimer`, Kafka's purgatory) | O(1) insert/cancel at millions of timers | tick-granular, more code. Name it and move on |

The staff answer usually combines **lazy expiry on read** for correctness with **one** of the
cleanup strategies for memory, sized to the expected number of live entries.

### Distributed delta

- In-process timers die with the process. A durable hold needs its `expiresAt` **stored** (a DB
  column, or a Redis key TTL), with the read path still checking it.
- Clocks differ across machines. Never compare an `expiresAt` written by host A with `now()` on
  host B for anything that needs better than "roughly" precision. Either let one authority
  decide (the DB's `now()`, or a Redis TTL), or use leases with a safety margin
  ([Topic 13](../13-lld-to-hld-seams/)).

### When NOT to inject time

- **Pure functions over a given instant.** `isActive(hold, now)` takes `now` as a parameter.
  That's even better than a clock field, and the method is trivially testable.
- **Logging timestamps.** Leave them to the logging framework.

### Interview move

The first time you write `now`, say: *"I'll inject a `Clock` so expiry is testable at the exact
boundary, and use `nanoTime` for timeouts because the wall clock can jump. Holds are half-open
`[created, expires)`."* That one sentence pre-empts three follow-up questions.

---

## 4. Official References

- `java.time.Clock` (the class Javadoc explains *why* to inject it):
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Clock.html
- `java.time.InstantSource` (Java 17+):
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/InstantSource.html
- `System.nanoTime()` (monotonic, origin-free, compare with `t1 - t0 < 0`):
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/System.html#nanoTime()
- `ScheduledExecutorService` and `Future.cancel` semantics:
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ScheduledExecutorService.html
- `ConcurrentHashMap.remove(Object key, Object value)` (conditional remove):
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html#remove(java.lang.Object,java.lang.Object)
- Netty `HashedWheelTimer`: https://netty.io/4.1/api/io/netty/util/HashedWheelTimer.html
- Varghese & Lauck (1987), *Hashed and Hierarchical Timing Wheels*, the paper behind timing wheels.

---

## 5. Key Takeaways

1. Time is a dependency. Inject `Clock` (what time is it), `Ticker` (how long has it been) and
   `Scheduler` (run this later).
2. Wall clocks jump, so timeouts and rates use `nanoTime` differences, never raw values.
3. Use half-open intervals `[start, end)`. Test at `end - 1ns` and exactly `end`.
4. The read path decides expiry. Timers only reclaim memory, so late or lost timers are harmless.
5. A timer removes **only what it created**: `remove(key, value)`, or an id check.
6. Virtual time (`ManualScheduler`) turns minutes of waiting into microseconds and makes the
   result exact.
7. Choose a cleanup strategy (lazy, per-entry timer, sweep, timing wheel) by the expected count
   of live entries.
8. Across machines, one authority owns "now", or you design with leases and margins.

---

**Self-grade: STRONG.** Three time-bug classes, each reproduced by a deterministic test at the
exact boundary, plus fixes, a reusable virtual-time toolkit (`MutableClock`, `ManualTicker`,
`ManualScheduler`) for every later topic, a cleanup-strategy trade-off table and the distributed
delta. Gap: the hashed timing wheel is described, not coded (Topic 23 is its home).

[← Topic 09: Executors & Backpressure](../09-executors-and-backpressure/) · [Index](../README.md) · [Topic 11: Money, IDs & Idempotency →](../11-money-ids-idempotency/)
