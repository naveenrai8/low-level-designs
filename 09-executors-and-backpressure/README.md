# Topic 09: Executors and Backpressure

An *executor* is a pool of worker threads that runs tasks you hand it. `Executors.newFixedThreadPool(10)` looks like it limits your work to 10. It only limits the *threads*. The queue in front of the threads has no limit. When producers send work faster than the 10 workers can finish it, tasks pile up in memory. Waiting time grows without end and nobody is told "no". Eventually the heap fills up. Most LLD components that "process things in the background" (schedulers, pub-sub, loggers, notification senders) fail this way. The staff move is to say where the limit is, and what happens at the limit, before writing `submit`.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [Key Takeaways](#5-key-takeaways)

---

## 1. The Problem

Every asynchronous design has a producer that can be faster than its consumer. If you set no limit, the extra work lands somewhere you didn't choose:

| Where the extra work goes | What you see | How it ends |
|---|---|---|
| An unbounded queue | p99 latency (the slowest 1% of requests) climbs for minutes and the heap grows | `OutOfMemoryError`, often in an unrelated thread |
| Unbounded threads (`newCachedThreadPool`) | thousands of platform (OS) threads | the OS runs out of memory: `unable to create native thread` |
| The shared `ForkJoinPool` | unrelated `parallelStream()` and `CompletableFuture` work stalls | the whole JVM slows down and nobody can trace why |
| Nowhere (silently dropped) | "some notifications never arrive" | a data-loss bug found by a customer |

**Backpressure** means the overloaded consumer pushes back on the producer. It can block the producer, slow it down, or reject it fast. Then the overload is visible and limited, not hidden and fatal.

What the interviewer probes when your design has a worker pool:

- Can you name the **four settings** (core, max, queue, rejection) and how they interact?
- Is every queue **bounded**, and is the over-limit behaviour a choice you made?
- Do you shut down cleanly and handle `InterruptedException` correctly?
- Do you know when virtual threads change the answer, and when they don't?

---

## 2. How It Solves It

### ThreadPoolExecutor: the real algorithm

`ThreadPoolExecutor` (TPE) is the class behind most pools. `execute(task)` decides in this order (from its Javadoc):

```
running < corePoolSize ?  ──yes──> start a NEW thread for this task (even if others are idle)
        │ no
queue.offer(task) ok ?    ──yes──> queued; an existing thread will take it
        │ no (queue full)
running < maximumPoolSize ? ─yes─> start a new (non-core) thread
        │ no
RejectedExecutionHandler.rejectedExecution(task)
```

Worked example: core 2, max 3, queue capacity 2, and six slow tasks arrive in a row.

| Task | What the pool does | Threads | Queue |
|---|---|---|---|
| 1 | below core, starts thread 1 | 1 | 0 |
| 2 | below core, starts thread 2 | 2 | 0 |
| 3 | at core, queue has room | 2 | 1 |
| 4 | queue has room | 2 | 2 |
| 5 | queue full, below max, starts thread 3 | 3 | 2 |
| 6 | queue full and at max, rejected | 3 | 2 |

The fact nobody remembers: **threads above core are only created when the queue is full.** An unbounded queue is never full, so `maximumPoolSize` does nothing.

| Factory | core / max | Queue | What really limits it |
|---|---|---|---|
| `newFixedThreadPool(n)` | n / n | `LinkedBlockingQueue` (no limit) | threads yes, **waiting work no** |
| `newSingleThreadExecutor()` | 1 / 1 | no limit | same |
| `newCachedThreadPool()` | 0 / `Integer.MAX_VALUE` | `SynchronousQueue` (holds nothing) | **nothing**: one thread per task running now |
| `newVirtualThreadPerTaskExecutor()` | one virtual thread per task | none | nothing. You add the limit (see below) |

### A bounded pool, with every choice visible

```java
ThreadPoolExecutor pool = new ThreadPoolExecutor(
        8, 8,                                   // core = max: a fixed pool; growth logic is a trap anyway
        0, TimeUnit.MILLISECONDS,               // keep-alive irrelevant when core == max
        new ArrayBlockingQueue<>(200),          // THE bound: at most 200 tasks waiting
        Thread.ofPlatform().name("notify-", 0).factory(),  // named threads: readable thread dumps
        new ThreadPoolExecutor.AbortPolicy());  // over the bound: fail fast, caller decides
```

Pick the queue size with arithmetic, not taste. Decide how long a task may wait before the wait is pointless. Then: `queue ≤ maxAcceptableWait × throughput`.

Worked example (illustrative numbers): 8 threads at 50 ms per task is 160 tasks per second. If waiting more than 1 s is useless to the caller, a queue above about 160 only holds work that will be too late. This is Little's law, `L = λ × W` (items waiting = arrival rate × time each waits). See [SystemDesign-poc 62](../../pocs/SystemDesign-poc/62-performance-laws-and-queueing/).

### Rejection policies: what happens at the limit

| Policy | What it does | Use when | Danger |
|---|---|---|---|
| `AbortPolicy` (default) | throws `RejectedExecutionException` | the caller can answer 429/503 or retry later | a caller that doesn't catch it crashes the request thread |
| `CallerRunsPolicy` | the submitting thread runs the task itself | you want the producer to slow to the consumer's speed | the caller may be an event-loop or request thread that then blocks. It also silently drops tasks after shutdown |
| `DiscardPolicy` | silently drops the new task | the work is truly lossy (metric samples) | invisible data loss. Always count the drops |
| `DiscardOldestPolicy` | drops the oldest queued task and tries again | "latest value wins" (UI refresh, positions) | starves old work, and is odd with priority queues |
| Producer blocks (`queue.put`) | not built in. Use a `BlockingQueue` directly or a `Semaphore` | in-process pipelines where waiting is right | deadlock if the consumer itself submits to the same full queue |

### Errors: `execute` vs `submit`

```java
pool.execute(() -> { throw new IllegalStateException("boom"); }); // -> thread's UncaughtExceptionHandler (logged)
Future<?> f = pool.submit(() -> { throw new IllegalStateException("boom"); }); // -> captured in f; SILENT
f.get();  // only now: ExecutionException wrapping the ISE
```

If nobody calls `get()` on a `submit`ted future, the exception disappears. For fire-and-forget work, use `execute`, or wrap the task body in a try/catch that logs.

### Graceful shutdown (the `ExecutorService` Javadoc idiom)

```java
/** Shuts a pool down in three steps: ask nicely, wait, then interrupt. */
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

Since JDK 19, `ExecutorService` is `AutoCloseable`. `close()` calls `shutdown()` and waits for the pool to finish. So `try (var pool = ...) { ... }` is a correct scope for short-lived pools.

**Interrupt manners in task code.** `InterruptedException` is how `shutdownNow()` asks your task to stop. Either pass it up, or catch it, clean up, and call `Thread.currentThread().interrupt()` before you return. `catch (InterruptedException e) {}` makes the thread impossible to stop, and shutdown then hangs for the full timeout.

### Virtual threads (JEP 444, final in JDK 21)

A virtual thread is a very light thread managed by the JVM, not the OS. They are cheap enough to create one per task. That suits **blocking I/O** (HTTP calls, JDBC), where pools of platform threads were only ever a workaround for costly threads.

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

- **Don't pool virtual threads.** Pools exist to limit and reuse *costly* threads. Instead, limit *how many calls run at once* against the scarce resource with a `Semaphore`.
- **They don't add CPU.** CPU-heavy work still wants about as many platform threads as cores.
- **Pinning.** On JDK 21–23, a virtual thread that blocks inside `synchronized` is stuck to its carrier thread (the OS thread it runs on). Enough of those and the carriers stall. So on those versions use `ReentrantLock` around blocking calls. **JEP 491 (JDK 24)** removed pinning for `synchronized`. Pinning still happens when native code calls back into blocking Java code, and when blocking inside class initialisers.
- **`ThreadLocal` caches** that were fine for 200 pooled threads can cost real memory across 1M virtual threads.

### `CompletableFuture`'s hidden pool

`supplyAsync(fn)` with no executor runs on `ForkJoinPool.commonPool()`. That pool is sized for CPU work (by default, cores minus 1 threads) and is shared by the whole JVM, including `parallelStream()`. Put blocking calls in it and you starve everyone else who uses it. **Always pass an executor** for I/O: `supplyAsync(fn, ioExecutor)`.

---

## 3. The Gaps

### Common misconceptions

| "I thought…" | Actually |
|---|---|
| "`maximumPoolSize` lets the pool grow under load" | Only once the queue is full. With an unbounded queue, never |
| "`newFixedThreadPool` is bounded" | Its threads are. Its queue is a `LinkedBlockingQueue` with capacity `Integer.MAX_VALUE` |
| "`CallerRunsPolicy` never loses work" | After `shutdown()` it drops tasks silently. It can also block a thread that must never block |
| "If my task threw, I'd see it in the logs" | Not with `submit`, unless someone calls `get()` |
| "Virtual threads remove the need for backpressure" | They remove the shortage of threads. The DB still has 50 connections, so you still need a limit |
| "`shutdown()` stops running tasks" | It only stops *accepting*. `shutdownNow()` interrupts, and tasks must obey the interrupt |
| "A bigger queue absorbs bursts" | Past `maxWait × throughput` it only stores work that will time out. Delay rises and the success rate doesn't |

### A failure cascade to name in an interview

Here it is as steps, with the same illustrative numbers.

1. The downstream service slows from 50 ms to 500 ms.
2. Each thread now does 10× less work.
3. The unbounded queue grows.
4. Queued requests wait longer than the client timeout.
5. Clients retry, so arrivals double.
6. The heap fills and GC thrashes.
7. The service dies and its peers inherit the load.

Worked example: a pool does 160 tasks/s at 50 ms. At 500 ms it does 16 tasks/s while 160 still arrive each second, so the queue grows by about 144 tasks every second. After 10 s that is about 1,440 tasks waiting, each over 1 s late.

A bounded queue plus `AbortPolicy` cuts the chain at step 3 with fast 503s. Add a circuit breaker ([Topic 27](../27-circuit-breaker-and-retry/)) and you also stop paying the 500 ms.

### When NOT to use an executor at all

- **Strict order per key** (events for one account must apply in order): a shared pool reorders them. Use one serial queue per key, or send `hash(key) % N` to N single-thread executors.
- **Scheduled or delayed work that must be saved:** `ScheduledThreadPoolExecutor` lives in memory and loses everything on restart. That is a job scheduler ([Topic 23](../23-delayed-job-scheduler/)).
- **Work that must survive the process:** an in-memory queue is not durable. Use a broker or an outbox.

### Interview moves

- Say the limit out loud: "Bounded queue of 200, `AbortPolicy`. The caller turns a rejection into a 503."
- Prefer **core = max** in interviews. It removes the queue-versus-growth subtlety from your design.
- Give your shutdown plan in one sentence, and restore the interrupt flag in any catch you write.
- Building the pool yourself is a separate problem (Topic 22). Don't rebuild `ThreadPoolExecutor` inside another design.

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

1. Every async boundary needs an explicit **limit** and an explicit **behaviour when the limit is hit**.
2. `ThreadPoolExecutor` grows past core **only when the queue is full**. An unbounded queue makes `max` meaningless.
3. `newFixedThreadPool` and `newSingleThreadExecutor` have unbounded queues, and `newCachedThreadPool` has unbounded threads.
4. Size queues from `maxAcceptableWait × throughput`. Bigger queues only add delay.
5. `AbortPolicy` fails fast. `CallerRunsPolicy` slows the producer (check which thread that is). Discard policies lose data, so count it.
6. `submit()` hides exceptions inside the `Future`. Use `execute()` or log in the task.
7. Shutdown: `shutdown`, then `awaitTermination`, then `shutdownNow`. **Restore the interrupt flag**.
8. Virtual threads: one per task, never pooled, limited by a `Semaphore`. On JDK 21–23 avoid blocking inside `synchronized`.
9. Never run blocking work on `ForkJoinPool.commonPool()`. Pass an executor to `*Async`.

---

[← Topic 08: Multi-Object Atomicity](../08-multi-object-atomicity/) · [Index](../README.md) · [Topic 10: Time and Testability →](../10-time-and-testability/)

**Self-grade: STRONG.** It covers the TPE decision algorithm, a factory trap table, rejection trade-offs, the failure cascade, virtual-thread rules with accurate JEP 444/491 scope, and when-not-to. Numbers are illustrative arithmetic, not measurements, and there's no runnable module (that's Topic 22's job).
