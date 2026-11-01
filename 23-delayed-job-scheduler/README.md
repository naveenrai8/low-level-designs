# Topic 23: Delayed Job Scheduler

"Run this later" sounds easy: sleep, then call it. The interviewer is waiting for the places where that breaks: two jobs due at the same instant, a cancel that lands just as the job fires, a slow periodic job that drifts, a failing job that retries forever, and a million timers that make a heap slow.

A staff answer keeps time injectable (so tests never sleep), makes cancel-versus-fire a clean either/or, says out loud what happens to missed periodic runs, and knows when a timing wheel beats a heap.

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
| **Job** | A piece of work to run later. It may throw. |
| **Due time** | The moment a job should start. |
| **Virtual time** | Time that only moves when the test moves it. The scheduler asks an injected `Ticker` ("clock that counts nanoseconds") instead of the real clock. See [Topic 10](../10-time-and-testability/). |
| **Driver** | Whoever calls `runDue()`. The scheduler starts no threads of its own. In production a loop calls it; in tests the test does. |
| **Fixed-rate** | A repeating job whose starts sit on a grid: start, start + period, start + 2 x period. It keeps the pace. |
| **Fixed-delay** | A repeating job that waits a gap after each run *ends*. It keeps the gap, so every slow run pushes all later runs back (this slide is called *drift*). |
| **Tie-breaker** | An extra number that orders two items that are otherwise equal. Here it is the *sequence number*: the order jobs were first scheduled in. |
| **Tombstone** | A cancelled job left in the heap and thrown away later, instead of being dug out (digging out costs O(n)). |
| **Backoff / jitter** | Backoff: wait longer after each failure (10, 20, 40 ms). Jitter: randomness mixed into the wait so many failed jobs do not all retry at the same instant. |
| **Dead-letter list (DLQ)** | Where failures are recorded for a human or another process to look at. |
| **Tick / wheel / bucket** | In a timing wheel, time is cut into ticks. The wheel is a circular array of buckets, one per tick position. |
| **Compare-and-set (CAS)** | "Change X from A to B only if it is still A", as one atomic step. |

---

## 1. The Problem

### What breaks without one

A service needs "send the reminder in 30 minutes", "retry the payment in 10 seconds", "refresh the cache every minute". A `Thread.sleep` per job burns a thread per job, cannot be cancelled cleanly, and cannot be tested without waiting. A scheduler turns "later" into data (a due time in a queue) so one driver can serve millions of jobs.

### What the interviewer is really probing

| Signal | What they want to see |
|---|---|
| Time seam | The clock is injected; tests move it by hand |
| Ordering | Equal due times run in a defined order (FIFO), not whatever the heap does |
| Cancel semantics | Cancel racing the fire has exactly one winner |
| Periodic semantics | Fixed-rate vs fixed-delay, and what happens to missed runs |
| Failure isolation | A job that throws does not kill the scheduler |
| Data-structure judgement | Heap vs timing wheel, with numbers |

### v1 requirements

1. Schedule a job after a delay or at an absolute time. Get a handle back.
2. Cancel through the handle.
3. Repeat at a fixed rate or with a fixed delay.
4. All of it on virtual time: `runDue()` runs what is due at the ticker's current reading.
5. A throwing job is recorded, never propagated.

### Clarifying questions (with the answer I assume)

| Question | Assumed answer | Why it matters |
|---|---|---|
| Who owns the threads? | The caller drives `runDue()`; handing started jobs to a pool is a one-line change ([Topic 22](../22-blocking-queue-thread-pool/)) | Keeps the core deterministic |
| Same due time: which first? | FIFO by first scheduling order | Without a rule the order is accidental |
| A periodic run is slow: skip or catch up? | Fixed-rate catches up (default) or coalesces (option) | Decides burst versus lost runs |
| Does a job survive a restart? | No for v1; F3 | Needs storage |

Out of scope: cron expressions, time zones, persistence (F3 is a design), leader election.

---

## 2. How It Solves It

### Entities and invariants

- `ScheduledJob`: job body, due time, sequence number, mode (once / fixed-rate / fixed-delay), status. It is also the `JobHandle` the caller holds.
- `TimerQueue`: stores jobs until due. `HeapTimerQueue` and `TimingWheel` implement it.
- `TimerScheduler`: the lock, the cancel/repeat/retry rules and the dead-letter list.

Invariants:

1. Status changes only by CAS, and only along these edges: `SCHEDULED -> RUNNING` (fire), `SCHEDULED -> CANCELLED` (cancel before fire), `RUNNING -> DONE` (finished), `RUNNING -> SCHEDULED` (a repeating or retrying job is re-queued) and `RUNNING -> CANCELLED` (a repeating or retrying job is cancelled mid-run). `DONE` and `CANCELLED` are final.
2. A `SCHEDULED` job is in the queue. The reverse is looser: the queue can also hold tombstones (cancelled jobs the heap has not yet discarded), and for a moment between "taken out of the queue" and "claimed" a `SCHEDULED` job is in neither place.
3. `pending()` counts jobs that will still start a run.
4. Ties: jobs due at the same instant run in the order they were *first* scheduled. A re-queued periodic job keeps its original sequence number (the JDK does the same).

### The interface

```java
JobHandle schedule(Job job, Duration delay);
JobHandle scheduleAt(Job job, long dueNanos);
JobHandle scheduleAtFixedRate(Job job, Duration initialDelay, Duration period);               // + a MissedRunPolicy overload
JobHandle scheduleWithFixedDelay(Job job, Duration initialDelay, Duration delay);
JobHandle scheduleWithRetry(Job job, Duration delay, RetryPolicy policy);
int runDue();            // runs everything due now, returns how many it started
List<FailedJob> deadLetters();
// JobHandle: id(), cancel(), status(), attempts()
```

Error contract: a null job, a negative delay or a non-positive period fails at scheduling time (`IllegalArgumentException` / `NullPointerException`). A delay, period or time too big to represent is clamped to "very far away" (never wraps to the past, never throws). A job that throws never throws out of `runDue()`; the failure goes to `deadLetters()`, which keeps only the newest 1,000 so a repeating job that fails every run cannot grow memory forever. If a job throws `InterruptedException`, the thread's interrupt flag is set again for the caller.

### Class sketch

```
 caller --schedule()--> TimerScheduler --add()--> TimerQueue (HeapTimerQueue | TimingWheel)
   ^                        | lock guards queue         |
   |                        v                           |
   +--- JobHandle <---- ScheduledJob <--pollDue(now)----+
 driver --runDue()--> poll -> CAS SCHEDULED->RUNNING -> run body (outside lock) -> requeue / done / dead-letter
```

### Algorithm 1: run what is due (with FIFO ties)

Three jobs. A and B are both due at 10 ms, scheduled in that order. C is due at 5 ms.

| Virtual time | Action | Heap key (due, seq) | Result |
|---|---|---|---|
| 0 | schedule A (10 ms) | (10, 0) | queued |
| 0 | schedule B (10 ms) | (10, 1) | queued |
| 0 | schedule C (5 ms) | (5, 2) | queued |
| 9 | `runDue()` | top is C (5, 2), due | C runs |
| 9 | same call, next | top is A (10, 0), not due | stop |
| 10 | `runDue()` | A (10, 0) then B (10, 1) | A, B: FIFO |

The heap compares `(due, seq)`. Without `seq`, A and B are equal and `PriorityQueue` may return either. Add and poll cost O(log n).

### Algorithm 2: cancel versus fire

`runDue()` takes a job out of the queue, then claims it with `CAS(SCHEDULED -> RUNNING)`. `cancel()` does `CAS(SCHEDULED -> CANCELLED)`. Both work on the same field.

| Order of events | Who wins the CAS | Outcome | `cancel()` returns |
|---|---|---|---|
| cancel, then fire | cancel | job never runs | true |
| fire (claim), then cancel | fire | job runs | false (plain one-shot) |
| cancel lands between "taken from queue" and "claim" | cancel | claim fails, job skipped | true |
| repeating or retrying job cancelled mid-run | cancel (`RUNNING -> CANCELLED`) | this run finishes, no later run starts | true |

There is no row where both or neither win. One subtle case: `cancel()` reads the status and then acts, and a repeating job can finish its run and go back to `SCHEDULED` in between. A single try would fail both CASes, return false and leave the job running forever. So `cancel()` loops: read, try the matching change, and read again if it lost.

### Algorithm 3: fixed-rate versus fixed-delay

Period 100 ms, every run takes 150 ms (virtual: the body advances the ticker). Start times in ms:

| Run | Grid slot (fixed-rate) | Fixed-rate start | Fixed-delay start (end of previous + 100) |
|---|---|---|---|
| 1 | 0 | 0 | 0 |
| 2 | 100 | 150 (slot 100 is overdue, runs as soon as run 1 ends) | 250 |
| 3 | 200 | 300 | 500 |
| 4 | 300 | 450 | 750 |
| 5 | 400 | 600 | 1000 |

Fixed-rate never slips off the grid: slot k is always `start + k x period`; the run is just late. Fixed-delay waits 100 after each run ends, so the gap is 250 and every later run slips. With a fast 30 ms body, fixed-rate starts are exactly 0, 100, 200 ...; fixed-delay starts are 0, 130, 260, 390.

**Catch-up policy.** If a stall skips several slots, the default `CATCH_UP` runs every missed slot back-to-back, then rejoins the grid. That is what `ScheduledThreadPoolExecutor` does: its `ScheduledFutureTask.setNextRunTime` computes the next time as `time += p` from the *scheduled* time, not from now ([JDK source](https://github.com/openjdk/jdk21u/blob/master/src/java.base/share/classes/java/util/concurrent/ScheduledThreadPoolExecutor.java)), and the `java.util.Timer` Javadoc calls the resulting burst "bunch up". The `ScheduledExecutorService` Javadoc adds that late executions "will not concurrently execute"; neither does this scheduler overlap a job with itself. `COALESCE` runs once and jumps to the next slot still in the future (a slot due exactly now is not "missed"): no burst, but runs are lost. Pick catch-up for "must happen N times" (billing ticks), coalesce for "refresh to the latest" (cache refresh).

### Algorithm 4: retries with backoff and jitter (F1)

Policy: 5 attempts, base 10 ms, cap 80 ms, equal jitter. After failure n the backoff is `min(cap, base x 2^(n-1))`, and the wait is a random value between half the backoff and all of it.

| Event | Backoff value | Wait (seed 42 test) | Result |
|---|---|---|---|
| attempt 1 fails | 10 | 6 ms | retry at +6 |
| attempt 2 fails | 20 | 16 ms | retry at +16 |
| attempt 3 fails | 40 | 28 ms | retry at +28 |
| attempt 4 fails | 80 | 80 ms | retry at +80 |
| attempt 5 fails | out of attempts | none | recorded in the dead-letter list |

A seeded `Random` makes the waits exact (the test asserts 6, 16, 28, 80, 52, 46, 68 for attempts 1 to 7; past the cap only the jitter moves). Why jitter: if 1,000 jobs fail together (a database blip) and all back off exactly 10, 20, 40 ms, they hit the database again in lock-step. "Equal jitter" keeps a floor of half the backoff, so a retry is never near-instant. `scheduleWithRetry` re-queues a failed job and counts attempts on the handle. Broker-side retry and DLQ: [event-processing-poc/core/10-dlq-retry-patterns](../../internal-pocs/event-processing-poc/core/10-dlq-retry-patterns/).

### Algorithm 5: the hashed timing wheel (F2)

A heap costs O(log n) per add. A timing wheel costs O(1). Example: wheel of 8 buckets, tick 1 ms.

| Time (tick) | Action | Bucket | Result |
|---|---|---|---|
| 0 | schedule X due at tick 3 | 3 mod 8 = 3 | in bucket 3 |
| 0 | schedule Y due at tick 11 | 11 mod 8 = 3 | in bucket 3 too, stored tick = 11 |
| 3 | wheel reaches bucket 3 | X.tick 3 <= 3 yes; Y.tick 11 <= 3 no | X is ready, Y stays |
| 5 | cancel Z (in bucket 5) | unlink from the bucket's list | O(1), nothing left behind |
| 11 | wheel reaches bucket 3 again | Y.tick 11 <= 11 | Y is ready |

The "hashed" part: a job more than one turn away shares a bucket with nearer jobs and waits for its turn; the stored tick is the check. In Varghese and Lauck's paper, scheme 5 keeps each bucket sorted and scheme 6 keeps it unsorted; this code is scheme 6 (O(1) add, the bucket is scanned when visited). Hierarchical wheels (the paper's scheme 7, used by Kafka) avoid rescanning far jobs by cascading from coarse wheels into fine ones; this code is a single level, like Netty's `HashedWheelTimer`.

Rules this implementation states:

1. A due time is rounded **up** to a whole tick: never early, up to one tick late.
2. Jobs in the same tick fire in scheduling order, *not* exact-time order inside the tick. With due times on the tick grid it is identical to the heap. A coarse-wheel test shows the difference (a 7 ms job scheduled before a 3 ms job fires first on a 10 ms wheel; the heap fires the 3 ms job first).
3. A jump in time visits at most one full turn of buckets (not every skipped tick), and nothing if no job is in a bucket.
4. Ready jobs go to a small (tick, seq) heap, so a zero-delay job scheduled from inside a job still runs in the right place.

**Measured: heap vs wheel.** Setup: Apple M3 Max, JDK 21.0.8, JVM max heap 24,576 MiB (printed by the benchmark; the parent pom fixes `argLine` to `-ea`, so an extra `-Xmx` flag is ignored). Workload: schedule N no-op jobs at random times in the next 60 s, cancel 20%, advance virtual time in 1 ms steps for 60 s firing everything. Wheel: 1 ms tick, 4,096 buckets. Each number is the best of 5 timed runs after 3 warm-up runs, and the whole benchmark ran 3 times (ms, heap | wheel):

| N | Run 1 | Run 2 | Run 3 |
|---|---|---|---|
| 1,000 | 0.8 \| 1.4 | 0.8 \| 0.5 | 0.8 \| 1.4 |
| 10,000 | 2.6 \| 1.3 | 2.4 \| 1.3 | 3.1 \| 2.0 |
| 100,000 | 46.8 \| 18.4 | 44.0 \| 15.1 | 45.9 \| 26.0 |
| 1,000,000 | 893.5 \| 628.2 | 915.2 \| 717.0 | 964.2 \| 359.3 |

Honest reading: the wheel is faster from 10,000 up (about 1.8 to 2.9 times at 100,000), but at 1M the ratio swings between 1.3 and 2.7 times across runs (median about 1.4), and at 1,000 the wheel was slower in two of three runs. The runs are noisy and I did not chase the cause. An earlier session on the same machine measured the heap at 716 to 752 ms at 1M and the wheel at 277 to 612 ms, so absolute numbers move between sessions. Treat the ratio as "wheel somewhat faster at scale", not a precise factor. The wheel's bigger win is cancel (O(1) unlink instead of a tombstone), and this workload cancels only 20%. One machine, a plain `nanoTime` harness, no JMH. Run it with [commands.md](commands.md).

### Concurrency model

- Shared state: the queue, sequence counter, pending count, dead-letter list. Primitive: one `ReentrantLock` around queue operations; the job body runs **outside** the lock, so a slow job never blocks `schedule` or `cancel`. With one lock there is no ordering problem ([Topic 08](../08-multi-object-atomicity/)).
- Per-job state: an `AtomicReference<JobStatus>`, changed only by CAS (invariant 1).
- Failure isolation: `execute` catches `Throwable`, like `FutureTask`, so even an `Error` cannot break the driver loop.

### Code and tests (88 run, plus 1 opt-in benchmark)

| File | What it shows | Risks covered |
|---|---|---|
| [`JobScheduler.java`](src/main/java/com/lld/scheduler/JobScheduler.java), [`TimerScheduler.java`](src/main/java/com/lld/scheduler/TimerScheduler.java) | Interface and error contract; lock, CAS claim, cancel loop, repeat, retry, saturating time math | all |
| [`HeapTimerQueue.java`](src/main/java/com/lld/scheduler/HeapTimerQueue.java), [`TimingWheel.java`](src/main/java/com/lld/scheduler/TimingWheel.java) | (due, seq) heap with tombstone purge; hashed wheel | ties, wheel |
| [`RetryPolicy.java`](src/main/java/com/lld/scheduler/RetryPolicy.java) | Capped exponential backoff, injected-random equal jitter | retries |
| [`SchedulerContractTest.java`](src/test/java/com/lld/scheduler/SchedulerContractTest.java) | Both queues: virtual time, ordering, drift tables, catch-up/coalesce, retries, failure isolation (`throwingJobDoesNotStopTheScheduler`) | time, ties, drift, retries, workers |
| [`CancelRaceTest.java`](src/test/java/com/lld/scheduler/CancelRaceTest.java), [`CancelEdgeTest.java`](src/test/java/com/lld/scheduler/CancelEdgeTest.java) | Same-thread probes for cancel inside the gap and after the claim; a barrier-aligned real-thread race; cancel parked mid-run; cancel of a retrying job | cancel |
| [`DifferentialTest.java`](src/test/java/com/lld/scheduler/DifferentialTest.java) | Wheel vs heap on seeded random programs (one-shot, fixed-rate, fixed-delay, job-schedules-job, cancel, jumps up to 200 ms) | wheel |
| [`OverflowTest.java`](src/test/java/com/lld/scheduler/OverflowTest.java), [`HeapTimerQueueTest.java`](src/test/java/com/lld/scheduler/HeapTimerQueueTest.java) | Huge delays and periods clamp; purge keeps live jobs in order | time, cancel |
| [`ScaleBenchmarkTest.java`](src/test/java/com/lld/scheduler/ScaleBenchmarkTest.java) | Opt-in benchmark (the only real `nanoTime`) | wheel |

---

## 3. The Gaps

### F1 and F2 (built)

F1 is Algorithm 4 and F2 is Algorithm 5 with the measured table. Choose the heap for exact times and under about 100k timers; choose the wheel for huge numbers of timeouts that are mostly cancelled and where one tick of imprecision is fine.

### F3: Durable jobs and exactly-once-ish execution (design delta only)

In-memory jobs vanish on restart. The delta:

1. **Store.** A `jobs` table: `id, due_at, payload, status, attempts, lease_until, owner`. Scheduling is an insert; the queue becomes an index on `(status, due_at)`.
2. **Claim with a lease.** A worker claims due rows with `UPDATE ... SET status='RUNNING', lease_until=now+L, owner=me WHERE id=? AND status='SCHEDULED'` (the same CAS, now in SQL).
3. **Crash recovery.** A `RUNNING` row whose lease expired is reclaimed and run again. That gives **at-least-once**.
4. **Exactly-once-ish.** True exactly-once across a crash is impossible (the job may finish and the worker die before recording it). So make the *effect* idempotent: pass the job id as an idempotency key ([Topic 11](../11-money-ids-idempotency/), [event-processing-poc/core/09-idempotency-dedup](../../internal-pocs/event-processing-poc/core/09-idempotency-dedup/)). At-least-once plus an idempotent effect is "exactly-once-ish".
5. **Fencing.** A slow worker whose lease expired must not write after the new owner: check `owner` (or a version number) in the completion `UPDATE`.
6. **Hybrid.** Load only the next window of rows (say the next minute) into the in-memory wheel or heap, and let the database answer the long-range question.

### Misconceptions

| # | Myth | Reality |
|---|---|---|
| 1 | "A priority queue keeps insertion order for equal keys." | It does not. Add a sequence number, and keep it when a periodic job is re-queued |
| 2 | "Cancel just removes it from the queue." | It must also beat a fire already in flight, and a run already started. The CAS on status decides |
| 3 | "An exception in a periodic job ends the schedule." | Here it is recorded and the schedule goes on. In `ScheduledThreadPoolExecutor` it silently *stops* all future runs ([Java-poc 34](../../internal-pocs/Java-poc/concurrency/34-scheduled-executors/)) |
| 4 | "Retry with backoff is enough." | Without jitter, correlated failures retry in lock-step |
| 5 | "Exactly-once is a scheduler feature." | It is at-least-once plus an idempotent effect |

### Trade-offs, and production delta

- This design needs a driver calling `runDue()`. For plain in-process timers on a pool use `ScheduledThreadPoolExecutor` ([Java-poc 34](../../internal-pocs/Java-poc/concurrency/34-scheduled-executors/); [capstone 58](../../internal-pocs/Java-poc/concurrency/58-capstone-job-scheduler/) is the pool-based version). To run jobs on workers, the driver hands each started job to the [Topic 22](../22-blocking-queue-thread-pool/) pool; cancel stays correct because the claim happens first. For cron and calendars use Quartz or a cloud scheduler.
- Production adds persistence and leases (F3), leader election so one node scans, the database clock for `due_at` (clock skew), metrics (lateness = actual start minus due, queue depth, dead-letter size).

---

## 4. Official References

- [`ScheduledExecutorService` (JDK 21 Javadoc)](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ScheduledExecutorService.html): fixed-rate vs fixed-delay, and the late-start, never-concurrent rule.
- [`ScheduledThreadPoolExecutor` (JDK 21 Javadoc)](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ScheduledThreadPoolExecutor.html) and its [source](https://github.com/openjdk/jdk21u/blob/master/src/java.base/share/classes/java/util/concurrent/ScheduledThreadPoolExecutor.java) (`setNextRunTime`, `triggerTime` overflow guard); [`java.util.Timer` Javadoc](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Timer.html) ("bunch up").
- Varghese and Lauck, ["Hashed and Hierarchical Timing Wheels: Data Structures for the Efficient Implementation of a Timer Facility", SOSP 1987](http://www.cs.columbia.edu/~nahum/w6998/papers/sosp87-timing-wheels.pdf).
- [Netty `HashedWheelTimer`](https://netty.io/4.1/api/io/netty/util/HashedWheelTimer.html) (single-level production wheel); [Kafka `TimingWheel`](https://github.com/apache/kafka/blob/trunk/server-common/src/main/java/org/apache/kafka/server/util/timer/TimingWheel.java) and [Confluent: Purgatory and Hierarchical Timing Wheels](https://www.confluent.io/blog/apache-kafka-purgatory-hierarchical-timing-wheels/).
- [AWS: Exponential Backoff and Jitter](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/) and [Builders' Library: Timeouts, retries and backoff with jitter](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/).

---

## 5. 45-Minute Interview Timeline

| Minutes | What to do |
|---|---|
| 0 to 12 | Clarify (who drives time, tie order, missed runs, durability), then interface first: `schedule`, `cancel`, `runDue`, error contract. Say "time is injected" |
| 12 to 22 | Heap keyed by (due, seq). Walk the same-instant example. Code `schedule` and `runDue` |
| 22 to 30 | Cancel: status CAS, tombstones. Name the race and how the CAS settles it |
| 30 to 36 | Periodic: draw the fixed-rate vs fixed-delay table. State catch-up vs coalesce |
| 36 to 45 | Retries (backoff, cap, jitter, dead-letter), then the timing wheel and when it wins; F3 in four sentences |

---

## 6. Senior vs Staff Rubric

| Signal | Senior | Staff |
|---|---|---|
| Time | Injects a clock | Drives everything by `runDue()` on virtual time and tests ordering exactly |
| Ties | Notices them when asked | Adds the sequence tie-breaker, keeps it on re-queue, tests it |
| Cancel | Removes from the queue | Names the cancel-versus-fire race, settles it with one CAS, handles the mid-run case |
| Periodic | Knows both flavours | Draws the drift table and picks catch-up or coalesce with a reason |
| Retries | Backoff | Cap, seeded jitter, attempt limit, DLQ, and the herd argument |
| Structure | Heap | Heap plus wheel, differential test, honest numbers |
| Durability | "Use a database" | Lease, CAS claim, fencing, at-least-once plus idempotent effect |

---

## 7. Key Takeaways

1. A scheduler is a queue of due times plus a driver. Keep time injected and everything becomes testable.
2. Equal due times need a sequence tie-breaker, kept for the job's whole life.
3. Cancel versus fire is one CAS on one field; read-then-CAS needs a retry loop.
4. Fixed-rate keeps the grid; fixed-delay slips by the run time. Say what happens to missed runs.
5. Saturate time arithmetic. A wrapped due time fires at once or loops forever.
6. Exactly-once is at-least-once plus an idempotent effect.

---

[← Topic 22: Blocking Queue and Thread Pool](../22-blocking-queue-thread-pool/) · [Index](../README.md) · [Topic 24: KV Store with TTL and Transactions](../24-kv-store-ttl-transactions/)

**Self-grade: STRONG.** Remaining gap: the benchmark is a plain `nanoTime` harness on one noisy machine (no JMH); F3 is a design only; the wheel is single-level (no hierarchical cascade).
