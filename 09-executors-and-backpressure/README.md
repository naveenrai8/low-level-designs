# Topic 09: Executors and Backpressure

`Executors.newFixedThreadPool(10)` looks like it bounds your work at 10. It bounds the *threads*.
The queue in front of them is unbounded, so when producers outrun the 10 workers, tasks pile up
in memory. Latency grows without limit and nobody is told "no". Eventually the heap fills. Most
LLD components that "process things in the background" (schedulers, pub-sub, loggers,
notification senders) fail this way. The staff move is to say where the bound is and what
happens at the bound before writing `submit`.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [Key Takeaways](#5-key-takeaways)

---

## 1. The Problem

Every asynchronous design has a producer that can be faster than its consumer. Without an
explicit bound, the gap is absorbed somewhere you didn't choose:

| Where the excess goes | Symptom | How it ends |
|---|---|---|
| An unbounded queue | p99 latency climbs for minutes, heap grows | `OutOfMemoryError`, often in an unrelated thread |
| Unbounded threads (`newCachedThreadPool`) | thousands of platform threads | native memory exhausted / `unable to create native thread` |
| The common `ForkJoinPool` | unrelated `parallelStream()` and `CompletableFuture` work stalls | the whole JVM slows down for reasons nobody can trace |
| Nowhere (silently dropped) | "some notifications never arrive" | a data-loss bug found by a customer |

**Backpressure** means the overloaded consumer pushes back on the producer: it blocks it, slows
it down, or rejects it fast. Then the overload is visible and bounded, not hidden and fatal.

What an interviewer probes when your design has a worker pool:

- Can you state the **four knobs** (core, max, queue, rejection) and how they interact?
- Is every queue **bounded**, and is the over-limit behaviour a **decision** you made?
- Do you shut down cleanly and handle `InterruptedException` correctly?
- Do you know when virtual threads change the answer, and when they don't?

---

## 2. How It Solves It

### ThreadPoolExecutor: the actual algorithm

`ThreadPoolExecutor.execute(task)` decides in this order (from its Javadoc):

```
running < corePoolSize ?  ──yes──> start a NEW thread for this task (even if others are idle)
        │ no
queue.offer(task) ok ?    ──yes──> queued; an existing thread will take it
        │ no (queue full)
running < maximumPoolSize ? ─yes─> start a new (non-core) thread
        │ no
RejectedExecutionHandler.rejectedExecution(task)
```

The consequence nobody remembers: **threads above core are only created when the queue is
full.** With an unbounded queue the queue is never full, so `maximumPoolSize` is dead config.

| Factory | core / max | Queue | What really bounds it |
|---|---|---|---|
| `newFixedThreadPool(n)` | n / n | `LinkedBlockingQueue` (unbounded) | threads yes, **queued work no** |
| `newSingleThreadExecutor()` | 1 / 1 | unbounded | same |
| `newCachedThreadPool()` | 0 / `Integer.MAX_VALUE` | `SynchronousQueue` (no capacity) | **nothing**: one thread per concurrent task |
| `newVirtualThreadPerTaskExecutor()` | one virtual thread per task | none | nothing. You add the bound (see below) |

### A bounded pool, with every decision visible

```java
ThreadPoolExecutor pool = new ThreadPoolExecutor(
        8, 8,                                   // core = max: a fixed pool; growth logic is a trap anyway
        0, TimeUnit.MILLISECONDS,               // keep-alive irrelevant when core == max
        new ArrayBlockingQueue<>(200),          // THE bound: at most 200 tasks waiting
        Thread.ofPlatform().name("notify-", 0).factory(),  // named threads: readable thread dumps
        new ThreadPoolExecutor.AbortPolicy());  // over the bound: fail fast, caller decides
```

Sizing the queue is arithmetic, not taste. Work out how long a task may wait before the wait is
pointless, then: `queue ≤ maxAcceptableWait × throughput`. Worked example (illustrative numbers):
8 threads at 50 ms per task is 160 tasks/s. If waiting more than 1 s is useless to the caller, a
queue above ~160 only stores work that will be too late. This is Little's law, `L = λ × W`; see
[SystemDesign-poc 62](../../pocs/SystemDesign-poc/62-performance-laws-and-queueing/).

### Rejection policies: what happens at the bound

| Policy | Behaviour | Use when | Danger |
|---|---|---|---|
| `AbortPolicy` (default) | throws `RejectedExecutionException` | the caller can return 429/503 or retry later | callers that don't catch it crash the request thread |
| `CallerRunsPolicy` | the submitting thread runs the task itself | a natural throttle: the producer slows down to consumer speed | the caller may be an event-loop or request thread, which then blocks; also silently drops tasks after shutdown |
| `DiscardPolicy` | silently drops the new task | genuinely lossy work (metrics samples) | invisible data loss. Always count the drops |
| `DiscardOldestPolicy` | drops the oldest queued task, retries | "latest value wins" (UI refresh, position updates) | starves old work; surprising with priority queues |
| Producer blocks (`queue.put`) | not a built-in policy; use a `BlockingQueue` directly or a `Semaphore` | in-process pipelines where waiting is correct | deadlock if the consumer itself submits to the same full queue |

### Errors: `execute` vs `submit`

```java
pool.execute(() -> { throw new IllegalStateException("boom"); }); // -> thread's UncaughtExceptionHandler (logged)
Future<?> f = pool.submit(() -> { throw new IllegalStateException("boom"); }); // -> captured in f; SILENT
f.get();  // only now: ExecutionException wrapping the ISE
```

If nobody calls `get()` on a `submit`ted future, the exception disappears. Fire-and-forget work
should use `execute`, or wrap the task body in try/catch that logs.

### Graceful shutdown (the `ExecutorService` Javadoc idiom)

```java
void stop(ExecutorService pool) {
    pool.shutdown();                                        // stop accepting; let queued work finish
    try {
        if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
            pool.shutdownNow();                             // interrupt workers, return queued tasks
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                log.error("pool did not terminate");        // a worker ignores interrupts: a bug to fix
            }
        }
    } catch (InterruptedException e) {
        pool.shutdownNow();
        Thread.currentThread().interrupt();                 // RESTORE the flag; never swallow it
    }
}
```

Since JDK 19, `ExecutorService` is `AutoCloseable`. `close()` calls `shutdown()` and waits for
termination, so `try (var pool = ...) { ... }` is a correct structured scope for short-lived
pools.

**Interrupt etiquette in task code.** `InterruptedException` is how `shutdownNow()` asks your
task to stop. Either propagate it, or catch it, clean up, and call
`Thread.currentThread().interrupt()` before returning. `catch (InterruptedException e) {}` makes
the thread un-stoppable, and shutdown then hangs for the full timeout.

### Virtual threads (JEP 444, final in JDK 21)

Virtual threads are cheap enough to create one per task, which is ideal for **blocking I/O**
(HTTP calls, JDBC) where platform-thread pools were only ever a workaround for expensive threads.

```java
Semaphore downstreamPermits = new Semaphore(50);   // the bound is now explicit: max 50 in flight to the DB
try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
    for (Request r : requests) {
        vt.submit(() -> {
            downstreamPermits.acquire();           // backpressure: waits cheaply (virtual thread parks)
            try { return callDatabase(r); }
            finally { downstreamPermits.release(); }
        });
    }
}
```

Rules:

- **Don't pool virtual threads.** Pooling exists to limit and reuse *expensive* threads. Limit
  *concurrency* against the scarce resource with a `Semaphore` instead.
- **They don't add CPU.** CPU-bound work still wants ~#cores platform threads.
- **Pinning.** On JDK 21–23, a virtual thread that blocks inside `synchronized` pins its carrier
  thread. Enough of those and the carrier pool stalls, so prefer `ReentrantLock` around blocking
  calls on those versions. **JEP 491 (JDK 24)** removed pinning for `synchronized`. Pinning still
  happens when native code calls back into blocking Java code, and when blocking inside class
  initialisers.
- **`ThreadLocal` caches** that were fine for 200 pooled threads can cost real memory across 1M
  virtual threads.

### `CompletableFuture`'s hidden pool

`supplyAsync(fn)` with no executor runs on `ForkJoinPool.commonPool()`. That pool is sized for
CPU work (parallelism = cores − 1 by default) and is shared JVM-wide, including by
`parallelStream()`. Put blocking calls in it and you starve every other user of it. **Always pass
an executor** for I/O: `supplyAsync(fn, ioExecutor)`.

---

## 3. The Gaps

### Common misconceptions

| "I thought…" | Actually |
|---|---|
| "`maximumPoolSize` lets the pool grow under load" | Only once the queue is full. With an unbounded queue, never |
| "`newFixedThreadPool` is bounded" | Its threads are. Its queue is `LinkedBlockingQueue` with capacity `Integer.MAX_VALUE` |
| "`CallerRunsPolicy` never loses work" | After `shutdown()` it discards silently. And it can block a thread that must never block |
| "My task threw, so I'd see it in the logs" | Not with `submit` unless someone calls `get()` |
| "Virtual threads remove the need for backpressure" | They remove thread scarcity. The DB still has 50 connections, so you still need a bound |
| "`shutdown()` stops running tasks" | It only stops *accepting*. `shutdownNow()` interrupts, and tasks must honour the interrupt |
| "A bigger queue absorbs bursts" | Past `maxWait × throughput`, it only stores work that will time out. Latency rises, and success rate doesn't |

### Failure cascade to name in an interview

Downstream slows from 50 ms to 500 ms → throughput per thread drops 10× → the unbounded queue
grows → queued requests exceed client timeouts → clients retry, so arrival rate doubles → heap
fills, GC thrashes → the service dies and its peers inherit the load. A bounded queue +
`AbortPolicy` cuts this at step 3 with fast 503s. Add a circuit breaker
([Topic 27](../27-circuit-breaker-and-retry/)) and you also stop paying the 500 ms.

### When NOT to use an executor at all

- **Strict per-key ordering** (events for one account must apply in order): a shared pool
  reorders. Use per-key serial queues, or partition by `hash(key) % N` onto N single-thread
  executors.
- **Scheduled/delayed work with persistence requirements:** `ScheduledThreadPoolExecutor` is in
  memory and loses everything on restart. That's a job scheduler
  ([Topic 23](../23-delayed-job-scheduler/)).
- **Work that must survive the process:** an in-memory queue is not durable. Use a broker or an
  outbox.

### Interview moves

- Say the bound out loud: "Bounded queue of 200, `AbortPolicy`. The caller maps rejection to 503."
- Prefer **core = max** in interviews. It removes the queue-vs-growth subtlety from your design.
- Name your shutdown story in one sentence, and restore the interrupt flag in any catch you write.
- Building the pool yourself is a separate problem (Topic 22). Don't reinvent `ThreadPoolExecutor`
  inside another design.

---

## 4. Official References

- `ThreadPoolExecutor` (core/max/queue rules, rejection policies): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html
- `Executors` (what each factory really builds): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Executors.html
- `ExecutorService` (the shutdown idiom, `close()`): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ExecutorService.html
- `CompletableFuture` (default async executor): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html
- JEP 444, Virtual Threads (JDK 21): https://openjdk.org/jeps/444
- JEP 491, Synchronize Virtual Threads without Pinning (JDK 24): https://openjdk.org/jeps/491
- Brian Goetz et al., *Java Concurrency in Practice*, ch. 8 "Applying Thread Pools" (saturation policies, sizing)

---

## 5. Key Takeaways

1. Every async boundary needs an explicit **bound** and an explicit **over-the-bound behaviour**.
2. `ThreadPoolExecutor` grows past core **only when the queue is full**. An unbounded queue makes
   `max` meaningless.
3. `newFixedThreadPool` and `newSingleThreadExecutor` have unbounded queues, and
   `newCachedThreadPool` has unbounded threads.
4. Size queues from `maxAcceptableWait × throughput`. Bigger queues only add latency.
5. `AbortPolicy` = fail fast. `CallerRunsPolicy` = throttle the producer (watch which thread
   that is). Discard policies = data loss, so count it.
6. `submit()` hides exceptions inside the `Future`. Use `execute()` or log in the task.
7. Shutdown: `shutdown` → `awaitTermination` → `shutdownNow`. **Restore the interrupt flag**.
8. Virtual threads: one per task, never pooled, bounded by a `Semaphore`. On JDK 21–23 avoid
   blocking inside `synchronized`.
9. Never run blocking work on `ForkJoinPool.commonPool()`. Pass an executor to `*Async`.

---

[← Topic 08: Multi-Object Atomicity](../08-multi-object-atomicity/) · [Index](../README.md) · [Topic 10: Time and Testability →](../10-time-and-testability/)

**Self-grade: STRONG.** It covers the TPE decision algorithm, a factory trap table, rejection
trade-offs, the failure cascade, virtual-thread rules with accurate JEP 444/491 scope, and
when-not-to. Numbers are illustrative arithmetic, not measurements, and there's no runnable
module (that's Topic 22's job).
