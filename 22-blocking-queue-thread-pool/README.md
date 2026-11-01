# Topic 22: Bounded Blocking Queue and Thread Pool

"Build a blocking queue" sounds like 20 lines: a list, a lock, and some `wait`. The interviewer is waiting for the places where that simple answer breaks:

1. A woken thread assumes the item is still there, but another thread took it first.
2. One signal wakes the wrong kind of thread, and the right one sleeps forever.
3. Shutdown either drops queued work by accident or hangs forever.

A staff answer names all three before being asked, shows a test that catches each one, and explains why `ArrayBlockingQueue` and `ThreadPoolExecutor` look the way they do.

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
| **Bounded queue** | A first-in-first-out line with a fixed maximum size. "Bounded" is what protects memory. |
| **Blocking** | If you cannot continue (queue empty or full), your thread sleeps until you can, instead of returning an error. |
| **Producer / consumer** | A producer adds items (`put`). A consumer removes them (`take`). |
| **Lock** | Only one thread may hold it at a time. All queue fields are touched only while holding it. |
| **Condition** | A waiting room attached to a lock. `await()` releases the lock and sleeps. `signal()` wakes one sleeper. |
| **Spurious wakeup** | A sleeper wakes with nobody having signalled. The Java docs allow this. |
| **Stolen wakeup** | You were signalled, but another thread got the lock first and used the item. When you finally run, it is gone. |
| **Lost signal** | A signal that reaches nobody useful, so a thread that needed it sleeps forever. |
| **Worker** | A pool thread that loops: take a task, run it. |
| **Backpressure** | Slowing the producers down when the consumers cannot keep up, instead of letting work pile up. |
| **Rejection policy** | The rule for a task the pool cannot take: throw, run it on the caller, or drop something. |

---

## 1. The Problem

What is really being probed: can you write the waiting code correctly (loops, rooms, signals) and then build a pool on it whose shutdown behaviour you can explain.

**v1 requirements**
- `BoundedQueue`: `put`/`take` that wait, `offer`/`poll` that never wait, timed versions, `close`.
- `FixedThreadPool`: N workers reading from the queue, `execute(Runnable)`.

**Clarifying questions (with assumed answers)**

| Question | Assumed answer |
|---|---|
| Can items be null? | No. `null` is refused, so `poll()` can use `null` to mean "empty". |
| What if the queue is closed while someone waits? | They are woken and get `QueueClosedException` (takers still get leftover items first). |
| Does the pool grow or shrink? | No: fixed size, started in the constructor. |
| What happens to a task that throws? | It is counted as failed. The worker survives. |

**Out of scope:** priorities, fairness guarantees, `Future`/`Callable`, scheduled tasks (that is Topic 23), the lock-free `LinkedTransferQueue`.

---

## 2. How It Solves It

### 2.1 Shape

```
 producers                                            workers (N threads)
 execute(task) --> [ BoundedBlockingQueue ] --take()--> run task, repeat
        |             one lock                              |
        | full?       notEmpty room: takers wait            | closed + empty? exit
        v             notFull  room: putters wait
 RejectionPolicy
```

### 2.2 Entities and invariants

- Shared state: ring buffer `items`, `head`, `count`, `closed`. Invariant: `0 <= count <= capacity`, oldest item at `head`.
- Every access happens while holding the one `ReentrantLock`.
- Two conditions: `notEmpty` (takers wait) and `notFull` (putters wait).

### 2.3 Timeline: why `while`, not `if`

Capacity 4. One taker T, one thief S who uses `poll()`. The `if` version waits once and trusts the wakeup.

| Step | What happens | Result |
|---|---|---|
| 1 | T calls `take()`, queue is empty, T waits | T asleep, lock released |
| 2 | A producer puts "first" and signals | T is awake but needs the lock |
| 3 | S gets the lock first and polls "first" | queue empty again |
| 4a | `if` version: T gets the lock and carries on as if an item were there | in the test class `IfWaitQueue` T returns `null` and count goes to -1. In the real queue the next check, `count == 0`, throws `QueueClosedException` on a queue that is still open |
| 4b | `while` version: T re-checks `count == 0`, waits again | T sleeps until "second" arrives and returns it |

What we learned by mutating the real `take()` from `while` to `if` (JDK 21.0.8, Apple-silicon laptop, one run each): `WaitLoopTest.whileWaitSurvivesTheStolenWakeup` fails in about 8 s total, mostly Maven start-up and the test's own 5 s bound. My first attempt, before the tests had time bounds, did not fail at all: it hung for over 2 minutes, because the failure surfaced as an exception on a worker thread and the test then waited forever. That is why every blocking call in the tests now has a bound and a message that names the bug.

The fix is one word: every `await` sits in `while (condition not true) { await(); }`. Spurious wakeups are handled by the same loop for free.

### 2.4 Timeline: why two conditions

Capacity 1. Takers T1 then T2 wait on the empty queue (the test forces this order). A producer puts "a". The signal wakes T1, but T1 cannot run until the producer lets go of the lock. The test forces that too: the producer's second put runs inside the first put's lock hold, through the `afterSignal` hook and the re-entrant lock, so the producer always reaches "b" before T1 can run.

| Step | One shared room, `signal()` | Two rooms (`notEmpty` / `notFull`) |
|---|---|---|
| 1 | T1, T2 wait. Producer puts "a" and wakes T1 | same |
| 2 | Producer tries "b" while still holding the lock: full, so the producer waits (forced, not luck) | same (waits in `notFull`) |
| 3 | T1 takes "a", signals. The room holds T2 and the producer, so it may pick T2 | T1's take signals `notFull`, which holds only putters |
| 4 | T2 sees an empty queue and sleeps again. Nobody wakes the producer | Producer wakes, puts "b", signals `notEmpty` |
| 5 | Producer stuck forever (a deadlock with no cycle) | T2 takes "b". Done |

Two rooms means each signal can only reach a thread that can use it, so plain `signal()` (cheaper than `signalAll`) is safe. With one room you would need `signalAll` everywhere, which wakes everyone to fight for the lock.

A signal sent before anyone waits is harmless in this design: the condition is stored in state (`count`), and a late taker checks `count` first and never sleeps (test `putBeforeTakeNeverWaitsSoNothingCanBeLost`).

### 2.5 The interface (error contract first)

`BoundedQueue<E>`: `put`, `take`, `offer`, timed `offer`, `poll`, timed `poll`, `size`, `capacity`, `close`, `isClosed`, `drainAll`. Contract: null items give `NullPointerException`; interrupted waits give `InterruptedException` and leave the queue unchanged; after `close`, puts give `QueueClosedException`, takers drain leftovers and then get `QueueClosedException`.

`TaskExecutor`: `execute`, `shutdown`, `shutdownNow`, `isShutdown`, `isTerminated`, `awaitTermination`.

### 2.6 Shutdown, step by step (F2)

Setup: 1 worker running a long task, tasks "1" and "2" queued.

| Call | What it does | Running task | Queued tasks | New tasks |
|---|---|---|---|---|
| `shutdown()` | closes the queue | finishes | both run, in order | refused |
| `shutdownNow()` | closes the queue, drains it, interrupts workers | gets interrupted (stops only if it reacts) | returned to the caller, never run | refused |
| `awaitTermination(t)` | waits for all workers to exit | n/a | n/a | n/a |

`shutdownNow` uses `closeAndDrain()`, which closes and empties the queue in ONE lock hold. With two steps (close, then drain) a busy worker could take a queued task in between and run it after `shutdownNow` returned, while that task was also reported as "never ran". `ShutdownTest.shutdownNowClosesAndDrainsInOneStep` forces that gap and fails on the two-step version.

Why close the queue instead of a flag plus poison pills: `close()` takes the queue's own lock, so "refuse new work" and "queue content" can never disagree (no task slips in after the check). It also wakes idle workers, which exit when they see `closed && empty`. `awaitTermination` measures its timeout with the injected `Ticker`, so tests expire it by moving a fake clock. Be precise about what that buys: the Ticker decides WHETHER to wait and how much time is left, but the sleep itself (`awaitNanos`) always uses the real clock. A test with a big fake step never reaches a sleep; a test with a small step really sleeps for about that long (`timedWaitSleepsOncePerRemainingSliceOfTime` spends about 3 ms).

### 2.7 Rejection policies and backpressure (F1)

`execute` never waits. If `offer` says full (or the queue is closed), the `RejectionPolicy` runs on the caller's thread.

| Policy | What happens to the refused task | Use when |
|---|---|---|
| abort (default) | `RejectedExecutionException` to the caller | you want to know, loudly |
| caller-runs | the caller runs it itself, so the caller is busy and cannot submit more | you want backpressure |
| discard | dropped silently | the work is optional (a metrics sample) |
| discard-oldest | the longest-waiting queued task is dropped, the new one is queued | fresh work beats stale work |

After shutdown, caller-runs, discard and discard-oldest all drop the task (running it would break "shutdown refuses new work"). The JDK does the same.

Small print, same as the JDK: discard-oldest drops the oldest queued task and retries, so under heavy contention it can drop more than one old task, and in theory the retry depth is unbounded. Its `isShutdown` guard is load-bearing: without it a shut-down pool would call the policy again forever (StackOverflowError). Caller-runs has a tiny gap between its `isShutdown` check and `run()`, so one task can run just after a shutdown.

### 2.8 Concurrency model

| Shared state | Invariant | Primitive | Why |
|---|---|---|---|
| ring buffer, `count`, `closed` | `0 <= count <= capacity` | one `ReentrantLock` | one lock keeps the invariant simple. `ArrayBlockingQueue` does the same |
| waiting threads | a sleeper is woken only when it can progress | two `Condition`s, `while` loops | see 2.3 and 2.4 |
| `liveWorkers` | reaches 0 only after all workers exit | small second lock + condition | so `awaitTermination` can sleep without polling |
| `completed`, `failed` | exact counts | `AtomicLong` | independent counters, no invariant between them |

One lock means a put and a take never run at the same time. `LinkedBlockingQueue` uses two locks (head and tail) for more throughput. Here, simplicity wins.

For the language-level background (wait/notify, `Condition`, executors) see the Java-poc concurrency guide instead of re-reading it here: [13 wait/notify](../../internal-pocs/Java-poc/concurrency/13-wait-notify-guarded-blocks/), [17 conditions](../../internal-pocs/Java-poc/concurrency/17-conditions/), [28 blocking queues](../../internal-pocs/Java-poc/concurrency/28-blocking-queues/), [31 ThreadPoolExecutor internals](../../internal-pocs/Java-poc/concurrency/31-threadpoolexecutor-internals/), [33 shutdown lifecycle](../../internal-pocs/Java-poc/concurrency/33-shutdown-lifecycle/), [49 backpressure and bounding](../../internal-pocs/Java-poc/concurrency/49-backpressure-and-bounding/).

### 2.9 How tests force the interleaving (no sleeping)

`QueueHooks` has two callbacks that run while the lock is held: `beforeAwait` (about to sleep) and `afterSignal` (just signalled). A test uses `beforeAwait` to learn that a thread is really asleep (a semaphore permit), and `afterSignal` to act in the gap between the signal and the woken thread getting the lock. That gap is exactly where stolen wakeups happen.

### 2.10 Code and tests

| File | What it is |
|---|---|
| [`BoundedQueue`](src/main/java/com/lld/blockingqueue/BoundedQueue.java) / [`BoundedBlockingQueue`](src/main/java/com/lld/blockingqueue/BoundedBlockingQueue.java) | interface with error contract, and the one-lock-two-conditions implementation |
| [`TaskExecutor`](src/main/java/com/lld/blockingqueue/TaskExecutor.java) / [`FixedThreadPool`](src/main/java/com/lld/blockingqueue/FixedThreadPool.java) | pool contract and the pool |
| [`RejectionPolicy`](src/main/java/com/lld/blockingqueue/RejectionPolicy.java) / [`RejectionPolicies`](src/main/java/com/lld/blockingqueue/RejectionPolicies.java) | the four policies |
| [`QueueHooks`](src/main/java/com/lld/blockingqueue/QueueHooks.java), [`Ticker`](src/main/java/com/lld/blockingqueue/Ticker.java) | test seams |
| [`IfWaitQueue`](src/test/java/com/lld/blockingqueue/IfWaitQueue.java), [`OneConditionQueue`](src/test/java/com/lld/blockingqueue/OneConditionQueue.java) | the two bug classes (test code only) |
| [`WaitLoopTest`](src/test/java/com/lld/blockingqueue/WaitLoopTest.java) | risk 1: `if` breaks under a stolen wakeup and under `signalAll` with two takers. The `while` versions pass the same steps |
| [`LostSignalTest`](src/test/java/com/lld/blockingqueue/LostSignalTest.java) | risk 2: one shared room strands the producer. Two rooms finish the same scenario |
| [`ShutdownTest`](src/test/java/com/lld/blockingqueue/ShutdownTest.java) | risk 3: `shutdown` vs `shutdownNow`, `awaitTermination` timing out on a fake clock, one-step `shutdownNow`, an `Error` in a task |
| [`RejectionPolicyTest`](src/test/java/com/lld/blockingqueue/RejectionPolicyTest.java) | risk 4: exact run lists for each policy |
| [`PoolConcurrencyTest`](src/test/java/com/lld/blockingqueue/PoolConcurrencyTest.java) | risk 5: 8 producers x 2,000 items behind a start gate, exact count and exact sum |
| [`BoundedQueueTest`](src/test/java/com/lld/blockingqueue/BoundedQueueTest.java) | FIFO and wrap-around, timeouts (the fake clock decides whether to wait and how much is left, but the sleep itself uses the real clock, see 2.6), interrupts, close, slot clearing, and a differential test against `ArrayDeque` |

---

## 3. The Gaps

### F1: rejection policies and backpressure

Covered in 2.7. The staff point: an unbounded queue has no rejection and no backpressure, it just runs out of memory later. Bounded plus caller-runs turns overload into slower producers. Abort turns it into an error the caller can retry. Discard turns it into data loss, so choose it on purpose. Link: [49 backpressure and bounding](../../internal-pocs/Java-poc/concurrency/49-backpressure-and-bounding/).

### F2: graceful shutdown

Covered in 2.6. Staff points:
- `shutdownNow` only sets interrupt flags. A task that ignores interrupts keeps running. Always bound `awaitTermination`, and escalate: `shutdown`, wait, `shutdownNow`, wait again, then log what is still alive.
- Returned tasks are the caller's responsibility (re-queue, persist, or report). They were accepted but never ran.
- Our `awaitTermination` returns `false` on timeout and the pool keeps working towards termination.

### F3: work-stealing vs virtual threads (discussion, not coded)

- **Our pool:** one shared queue, N platform threads. Every task goes through one lock, so the lock becomes the limit at high task rates.
- **Work-stealing (`ForkJoinPool`):** each worker has its own deque. It pushes and pops its own end without contention, and idle workers steal from the other end of a busy worker's deque. Wins for many small, CPU-bound tasks that spawn subtasks. Loses to a plain pool for blocking I/O tasks, because a blocked worker holds its deque hostage. See [35 fork/join work-stealing](../../internal-pocs/Java-poc/concurrency/35-forkjoin-work-stealing/).
- **Virtual threads (Java 21):** a thread is so cheap that you create one per task and stop pooling threads. The scarce resource moves from "threads" to "downstream capacity" (DB connections, rate limits), so you bound that with a `Semaphore` instead of a pool size. Pooling virtual threads is an anti-pattern. See [40 virtual threads](../../internal-pocs/Java-poc/concurrency/40-virtual-threads/).
- **Rule of thumb:** CPU-bound and recursive, use fork/join. Blocking I/O, use a virtual thread per task plus a semaphore. A fixed pool is still right when you must cap parallelism of heavy work or need predictable thread names and lifetimes.

### Misconceptions and common mistakes

| Myth or mistake | Reality |
|---|---|
| "`if (empty) wait()` is fine, I was signalled" | Another thread may have taken the item, and wakeups can be spurious. Always `while`. (WaitLoopTest) |
| "One condition and `signal()` is simpler" | It can wake the wrong kind of thread and strand the right one. (LostSignalTest) |
| "Use `signalAll` to be safe" | Correct but wasteful: every sleeper wakes and fights for the lock. Two rooms let `signal()` be both safe and cheap. We do use `signalAll` in `close()`, where everyone must wake. |
| "A signal before the waiter waits is lost, so the code is broken" | Only if the condition is not stored in state. Check `count` before waiting and it cannot matter. |
| "`shutdownNow` stops the tasks" | It interrupts. Stopping is up to the task. |
| "Catch `InterruptedException` and ignore it" | Either rethrow, or restore the flag with `Thread.currentThread().interrupt()`. |
| "A failing task just fails" | In a naive pool an uncaught exception kills the worker and the pool silently shrinks, and a 1-thread pool keeps accepting tasks that never run. We catch `Throwable` per task, so an `AssertionError` (likely under `-ea`) is counted as failed and the worker lives on. The JDK instead lets the thread die and starts a replacement worker. Our choice is simpler but also swallows fatal errors like `OutOfMemoryError`, so log them in real code. |

### Trade-offs and when not to use this

- One lock is simple but caps throughput. Use two locks (`LinkedBlockingQueue`) or lock-free structures if profiling shows contention.
- Do not hand-roll this in production. Use `ArrayBlockingQueue` and `ThreadPoolExecutor`. The value of this topic is knowing what they do.
- Fixed pools can deadlock if tasks wait for other tasks in the same pool (all workers blocked, nobody left to run the subtask).

### Compared with the JDK

| Aspect | This design | JDK 21 |
|---|---|---|
| Queue | one lock, `notEmpty` / `notFull`, ring buffer | the same design in [`ArrayBlockingQueue`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ArrayBlockingQueue.html), plus optional fairness and iterators |
| Pool size | fixed, started up front | [`ThreadPoolExecutor`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html): core and max size, keep-alive, threads created on demand, a state machine packed in one atomic int |
| Submit path | `offer` to the queue, else rejection policy | core threads first, then queue, then extra threads up to max, then rejection |
| Policies | abort, caller-runs, discard, discard-oldest | the same four ([`CallerRunsPolicy`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.CallerRunsPolicy.html) and friends) |
| Results | none (`Runnable` only) | `Future` via `ExecutorService.submit` |
| Hooks | none | `beforeExecute`, `afterExecute`, `terminated` |

### Production and distributed delta

- Name your threads and set an uncaught-exception handler, or failures vanish.
- Export queue depth, active workers, rejected count and task latency. Queue depth rising is the first sign of overload.
- A queue in memory dies with the process. For work that must not be lost, use a real broker (see [event-processing-poc](../../internal-pocs/event-processing-poc/)) and treat the in-process queue as a buffer only.
- Related earlier topics: [08 lock ordering and multi-object atomicity](../08-multi-object-atomicity/), [10 time and testability](../10-time-and-testability/) (why the `Ticker` is injected).

---

## 4. Official References

- [`ArrayBlockingQueue`, JDK 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ArrayBlockingQueue.html)
- [`ThreadPoolExecutor`, JDK 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html) (the rejected tasks and termination sections)
- [`Condition`, JDK 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/Condition.html) (spurious wakeups are explicitly allowed)
- [`ExecutorService`, JDK 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ExecutorService.html) (the two-phase shutdown example)
- Goetz et al., *Java Concurrency in Practice*: ch. 5 (blocking queues), ch. 7 (cancellation and shutdown), ch. 8 (thread pools and saturation policies), ch. 14 (building custom synchronizers, condition queues and the `while` rule).

---

## 5. 45-Minute Interview Timeline

| Minutes | What to do |
|---|---|
| 0-5 | Clarify: bounded, blocking, null policy, close behaviour. State the interface. |
| 5-20 | Code the queue: lock, two conditions, `while` loops. Say why two rooms out loud. |
| 20-30 | Pool on top: workers loop on `take`. `execute` uses `offer` and a rejection policy. |
| 30-38 | Shutdown: close-the-queue design, `shutdown` vs `shutdownNow`, bounded `awaitTermination`. |
| 38-45 | Tests (stolen wakeup, exact totals) and F3 discussion. |

If you are behind at minute 25, skip timed `offer`/`poll`. Name them, and keep the loops and shutdown correct.

## 6. Senior vs Staff Rubric

| Area | Senior (hire) | Staff (strong hire) |
|---|---|---|
| Waiting | Uses a lock and a condition | Explains stolen and spurious wakeups, uses `while`, and can break the `if` version on purpose |
| Signals | `signalAll` everywhere | Two rooms so `signal()` is safe; knows when `signalAll` is right (close) |
| Shutdown | Sets a flag | Closes through the queue's lock, distinguishes drain from interrupt, bounds the wait, returns unrun tasks |
| Overload | Unbounded queue | Bounded queue plus a chosen policy; explains caller-runs as backpressure |
| Tests | Happy path | Hook-driven interleavings, fake clock, exact totals behind a start gate |
| Compares | "Just use the JDK" | Knows where it differs from `ArrayBlockingQueue` and `ThreadPoolExecutor`, and when virtual threads change the answer |

## 7. Key Takeaways

1. Every `await` goes in a `while` loop. A wakeup means "check again", not "your item is here".
2. Two conditions (`notEmpty`, `notFull`) make a single `signal()` always reach a useful thread. One shared room can strand a thread.
3. Keep the real condition in state (`count`), so a signal that arrives early cannot be lost.
4. `shutdown` drains, `shutdownNow` interrupts and returns the unrun tasks. Interrupt is a request, not a kill.
5. Close through the queue's own lock so "no new work" and "queue content" cannot disagree.
6. A bounded queue needs a rejection policy. Caller-runs is backpressure, discard is data loss.
7. Test interleavings with hooks and latches, never sleeps. Assert exact totals.
8. With virtual threads the bottleneck moves from thread count to downstream capacity.

---

**Self-grade: STRONG.** Full spine, timelines before each algorithm, bug-then-fix tests for the `if` wait and the lost signal, exact-count tests for all four policies and both shutdown modes, and a JDK comparison with sources. Remaining gap: F3 is a discussion only (no code and no measured throughput comparison), and the pool is fixed-size (no core/max growth or keep-alive).

[← Topic 21: Rate Limiter](../21-rate-limiter/) · [Index](../README.md) · [Topic 23: Delayed Job Scheduler →](../23-delayed-job-scheduler/)
