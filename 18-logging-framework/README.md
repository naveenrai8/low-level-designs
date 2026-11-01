# Topic 18: Logging Framework

"Design a logging framework" looks like three classes: a Logger, an Appender and a Formatter. The interviewer is waiting for what happens when things go wrong:

1. One appender throws (disk full), and the other appenders or the caller's code break with it.
2. The app logs faster than the disk can write, so a queue grows without limit or the request threads stall.
3. A request id set on one thread is gone when the work moves to a thread pool.

A staff answer names all three, decides what is lost under overload, and counts every loss.

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
| **Level** | How important a line is: TRACE, DEBUG, INFO, WARN, ERROR. A logger has a threshold and drops lines below it. |
| **Logger** | The object application code calls. It has a dotted name such as `shop.orders.pay`. |
| **Appender** | A destination for lines: console, file, memory. |
| **Formatter** | Turns an event into text, either a readable line or JSON. |
| **Event** | One finished log line, frozen at the moment of logging. |
| **MDC** | Mapped Diagnostic Context: labels such as a request id stamped on every line the current thread logs. |
| **Bounded queue** | A queue with a maximum size, so memory cannot grow forever. |
| **Drop policy** | The rule for what to throw away when the bounded queue is full. |
| **Snapshot** | An immutable copy of the config. Swapping it is one step, so nobody sees half of a change. |

---

## 1. The Problem

### What breaks without a design

Logging runs inside every request. If it is slow, every request is slow. If it throws, the business code fails because a log line failed. If it is silent about its own failures, you find out the logs were missing during the incident.

### What the interviewer is really probing

| Signal | What they want to see |
|---|---|
| Failure isolation | A broken appender never stops the others and never throws into the caller, and the failures are counted |
| Overload thinking | A bounded queue, a named drop policy and exact counts of what was lost |
| Hot-path cost | A disabled level costs almost nothing: no string building, no allocation |
| Config safety | A reload is atomic, and a bad config does not break the running one |
| Context | MDC is copied to pool threads explicitly and cleaned up afterwards |
| Testability | Time is injected, and no test sleeps |

### v1 requirements

- Levels with a threshold, several appenders at once, and pluggable formatters.
- A failing appender is isolated and counted.
- Timestamps come from an injected `Clock`.

### Clarifying questions (assumed answers)

| Question | Assumed answer |
|---|---|
| Is logging allowed to lose lines? | Only under overload, only by a stated policy, and always counted |
| Must a failing appender be retried? | No. The event is lost for that appender and counted |
| Files, rotation, network sinks? | Out of scope. A `WriterAppender` takes any `Appendable` |
| Static `LoggerFactory.getLogger`? | Out of scope. We use an explicit `LoggerContext`, so config, loggers and counters are per context. The one global is `Mdc`, a static `ThreadLocal` shared by every context on that thread |

---

## 2. How It Solves It

### Entities and invariants

| Type | Job | Invariant |
|---|---|---|
| `Level` | Ordered importance, plus OFF | OFF blocks everything |
| `LogEvent` | One frozen line | Immutable. Built on the caller's thread, so thread name and MDC are the caller's |
| `Appender`, `LogFormatter` | Destination and text format | An appender may throw, and the context absorbs it |
| `LogConfig` | Levels, root level, appenders | Immutable. Changed only by building a new one |
| `LoggerContext` | Makes loggers, holds the current snapshot, fans out | One call reads the snapshot once and uses it for both the level check and the dispatch. A re-entrant call (an appender logging through the same context) is dropped and counted |
| `AsyncAppender` | Bounded queue plus one worker | `offered == delivered + failed + queued + abandoned + workerDeadDrops + dropped`, each term counted in a different place |
| `Mdc` | Thread labels | Copied explicitly, restored after the task |

### Class diagram

```
 Logger --asks--> LoggerContext --holds--> AtomicReference<Snapshot(LogConfig + level cache)>
    |                   | fan-out, catch + count failures
    | builds            v
    +--LogEvent--> [Appender] [Appender] [AsyncAppender --queue--> worker --> Appender]
                       |
                  LogFormatter (Pattern / Json)
```

### The interface (error contract first)

```java
public interface Appender {
    void append(LogEvent event);   // may throw RuntimeException; the context catches, counts and carries on
    default void close() {}        // idempotent
}
```

Caller-facing contract: `logger.info(...)` never throws because of an appender. Failures show up in `context.failureCount()`, `failuresFor(appender)` and `lastFailure()`.

### Isolation: a timeline

Config has three appenders in order: Broken, Memory, Other. The code logs two lines.

| Step | What happens | Result |
|---|---|---|
| 1 | `info("one")` goes to Broken | It throws. The context catches it, failures = 1 |
| 2 | Same event goes to Memory, then Other | Both receive it |
| 3 | `info("two")` | Failures = 2. Caller saw no exception |

Same pattern as the listener isolation in [Topic 05](../05-patterns-that-matter/).

### Async appender: a timeline

Capacity 3, policy DROP_NEWEST. The worker is stuck writing line 0 (a slow disk). Lines 1 to 7 arrive.

| Step | What happens | Result |
|---|---|---|
| 1 | Line 0 taken by the worker | Queue is empty, worker busy |
| 2 | Lines 1, 2, 3 arrive | Queue is full (3 of 3) |
| 3 | Lines 4, 5, 6, 7 arrive | Each is dropped, dropped = 4 |
| 4 | Disk recovers, `close()` | Worker writes 0, 1, 2, 3 |
| 5 | Check | offered 8 = accepted 4 + dropped 4, and delivered = 4 |

| Policy | Loses | Choose when |
|---|---|---|
| `DROP_NEWEST` | The line that just arrived | The first lines of an incident matter most |
| `DROP_OLDEST` | The oldest queued line (the same load keeps 0, 5, 6, 7) | The freshest state matters most |
| `BLOCK_WITH_TIMEOUT` | The new line, but only after the caller waited up to the timeout | You will pay latency to lose fewer lines. The wait is measured by the injected `Ticker` |

`close()` stops new events (later ones count as dropped), lets the worker write what is queued, closes the delegate, and is safe to call twice. A second caller of `close()` waits for the first. If the closing thread is interrupted, the queue is discarded and counted once in `abandoned()`, the delegate is still closed and the interrupt flag is restored. The wait for the worker has no timeout, so a delegate that never returns makes `close()` wait until its thread is interrupted. If the delegate throws an `Error`, the worker records its death, counts the event it was writing and everything queued as `workerDeadDrops()`, wakes blocked producers and rethrows. Later appends count as `workerDeadDrops` too. More on queues: [Java-poc 28](../../internal-pocs/Java-poc/concurrency/28-blocking-queues/) and [49 backpressure](../../internal-pocs/Java-poc/concurrency/49-backpressure-and-bounding/), and [Topic 09](../09-executors-and-backpressure/).

### Hierarchy and reload: a timeline

Levels: root=ERROR, `a`=INFO, `a.b.x`=WARN.

| Logger | Looks up | Effective level |
|---|---|---|
| `a.b.c` | `a.b.c`, `a.b`, then `a` | INFO |
| `a.b.x` | itself | WARN |
| `ab` | `ab`, no dot, so root | ERROR (`ab` is not a child of `a`) |

A reload builds a new `Snapshot` (config plus an empty cache of resolved levels) and swaps it with one `AtomicReference.set`. A call reads the snapshot once and passes it to both the level check and the dispatch, so it sees the old config or the new one, never half. A test proves it on one thread: a clock reloads the config in the gap between the two steps. Bad config text throws before anything is swapped, so the old config keeps running.

### Hot path cost

`isEnabled` is one map read and a compare. A disabled call never calls the supplier, never fills `{}` and gets a shared no-op builder from `event(...)`. The measured claim is narrower: `debug("constant")` and `event(level).field("k", 1).log("constant")` allocate 0 bytes over 200,000 calls (thread allocation counter, JDK 21.0.8, Apple silicon; the test is skipped on a JVM without the counter). A capturing lambda, or a boxed number above the Long cache, is allocated by the caller before the logger is entered, so guard those with `isEnabled`.

### MDC across threads: a timeline

| Step | What happens | Result |
|---|---|---|
| 1 | Request thread: `Mdc.put("req","42")` | Label is in this thread's ThreadLocal |
| 2 | Plain `pool.submit(task)` | Task on the pool thread sees `null`. A ThreadLocal does not cross threads |
| 3 | `pool.submit(Mdc.wrap(task))` | Wrap copies the labels now. The task sees `42` |
| 4 | Task ends (even by exception) | The worker's own labels are restored, so the next request sees nothing of `42` |

`Mdc.executor(pool)` wraps every task so nobody forgets. Locks, ThreadLocal and executors: [Java-poc concurrency](../../internal-pocs/Java-poc/concurrency/).

### Concurrency model

| Shared state | Primitive | Why |
|---|---|---|
| Current config | `AtomicReference<Snapshot>` | One atomic swap, lock-free reads |
| Level cache | `ConcurrentHashMap` inside the snapshot | Belongs to the snapshot, so it can never be stale |
| Async queue | `ReentrantLock` and two conditions | Size check and enqueue must be one step for exact counts |
| Counters | `AtomicLong` | Exact totals under contention |
| Writer output | `synchronized`, one `append` of text plus newline | Two threads never interleave a line through the same `WriterAppender` (two appenders on one target are not coordinated) |
| Re-entrancy | per-thread flag in the context | An appender that logs through its own context is dropped and counted, not recursed |

### Code and tests

| File | What it shows |
|---|---|
| [`Logger.java`](src/main/java/com/lld/logging/Logger.java), [`LoggerContext.java`](src/main/java/com/lld/logging/LoggerContext.java) | Hot path, fan-out, failure counters |
| [`AsyncAppender.java`](src/main/java/com/lld/logging/AsyncAppender.java) | Bounded queue, three drop policies, close |
| [`LogConfig.java`](src/main/java/com/lld/logging/LogConfig.java) | Immutable config and all-or-nothing parse |
| [`Mdc.java`](src/main/java/com/lld/logging/Mdc.java) | ThreadLocal and the capture-and-restore wrapper |
| [`JsonFormatter.java`](src/main/java/com/lld/logging/JsonFormatter.java), [`PatternFormatter.java`](src/main/java/com/lld/logging/PatternFormatter.java) | Structured and readable output |
| [`LoggerTest`](src/test/java/com/lld/logging/LoggerTest.java), [`AsyncAppenderTest`](src/test/java/com/lld/logging/AsyncAppenderTest.java), [`MdcTest`](src/test/java/com/lld/logging/MdcTest.java) | 53 tests, none sleeps, each with a timeout |

How to run: [commands.md](commands.md).

---

## 3. The Gaps

### Follow-up ladder

| Follow-up | Design delta | Status |
|---|---|---|
| F1: logging is slow under load | `AsyncAppender`: bounded queue, drop policy, exact counts, flush on close. The tests park the worker on a latch, fill the queue and count exactly | Coded |
| F2: different levels per package, change at runtime | Dotted-name hierarchy, immutable snapshot, atomic reload, level cache owned by the snapshot | Coded |
| F3: searchable logs and request ids | `JsonFormatter` and `logger.event(level).field(k, v).log(msg)`. Plus `Mdc.wrap`/`Mdc.executor` | Coded |

### Misconceptions

| Myth | Reality |
|---|---|
| "Async logging never blocks" | Only if the queue has room. A full queue must either drop or block, and you choose |
| "An unbounded queue is safe because logging is small" | A slow disk plus a burst fills memory. Bound it and count drops |
| "ThreadLocal context follows my task" | It stays on the thread. A pool needs an explicit copy (`MdcTest.plainThreadLocalDoesNotCrossToExecutorThread`) |
| "Catching exceptions in appenders is enough" | Only if you count them. A swallowed failure is a silent outage |
| "`logger.debug("x" + expensive())` is free when debug is off" | The argument is built before the call. Use a supplier or `{}` |

### Trade-offs and when not to

- Drops vs latency: DROP_* keeps callers fast and loses lines. BLOCK keeps lines and risks slow requests. Audit logs should not use a lossy logger at all. Write them to a durable store in the request path.
- The async worker reads `MDC` from the event, not its own thread, so context stays correct even though the write happens elsewhere.
- `Error` (such as OutOfMemoryError) is not caught by the context, so it reaches the caller on purpose. In the `AsyncAppender` worker it kills the thread, and that death is recorded and counted, never silent.
- Log forging (CWE-117): `PatternFormatter` writes CR and LF as the two characters backslash-r and backslash-n. `JsonFormatter` escapes them already. `JsonFormatter` writes only booleans and finite JDK numbers raw, so NaN, Infinity and custom `Number` types cannot break the JSON.
- `WriterAppender.close()` flushes the target, and closes it only if told it owns it.

### Production and distributed delta

- A real framework adds rolling files, a lifecycle hook that flushes on JVM shutdown, and a back-off for a sink that keeps failing (circuit breaker).
- In a fleet, ship JSON lines to a collector. Add a trace id to the MDC and propagate it in headers.
- Real libraries: Logback and Log4j2 (the latter has a lock-free ring-buffer async mode, see Java-poc 48).

### Honest limits

- The worker has one thread, so one very slow delegate delays its own queue only.
- A call used to read the config snapshot twice (once for the level check, once for the dispatch), so a reload in between could send a DEBUG line to the wrong appenders. That was a code bug, now fixed and covered by a same-thread probe plus a stress test.
- `LoggerContext` never shrinks its map of loggers (one small object per distinct name, so do not make a logger per request). Failure counters of appenders removed by a reload are forgotten.
- `BLOCK_WITH_TIMEOUT` waits for the exact remaining time. A test that moves the fake ticker must call the package-private `wake()` so the waiter re-reads it.
- `close()` waits for the worker with no timeout unless the closing thread is interrupted.
- `Mdc` is a static `ThreadLocal`, shared by all contexts.

---

## 4. Official References

- SLF4J manual: https://slf4j.org/manual.html
- Logback MDC: https://logback.qos.ch/manual/mdc.html
- `java.util.logging.Logger`: https://docs.oracle.com/en/java/javase/21/docs/api/java.logging/java/util/logging/Logger.html
- Log4j2 async loggers: https://logging.apache.org/log4j/2.x/manual/async.html
- Goetz, *Java Concurrency in Practice*, ch. 7 (shutdown, with a logging service example) and ch. 5 (blocking queues).

---

## 5. 45-Minute Interview Timeline

| Minutes | Do |
|---|---|
| 0-5 | Clarify: lossy or not, async, config, structured. State scope |
| 5-12 | Interfaces: Level, Appender (with error contract), Formatter, Logger |
| 12-22 | Code Logger and context fan-out with isolation and counters |
| 22-30 | Say the overload story: bounded queue, drop policy, counts |
| 30-38 | Hierarchy and atomic reload. Hot-path cost |
| 38-45 | MDC and the pool thread, tests with a fake clock, production delta |

---

## 6. Senior vs Staff Rubric

| Area | Senior | Staff |
|---|---|---|
| Appenders | Strategy pattern | Isolation contract, failure counters, never throws into the caller |
| Async | A queue and a thread | Bounded, named drop policy, exact accounting, flush on close |
| Config | Map of levels | Inheritance, immutable snapshot, atomic and all-or-nothing reload |
| Performance | "Check level first" | Zero allocation when disabled, lazy messages |
| Context | Uses MDC | Knows why it does not cross threads, wraps and restores |
| Testing | Happy path | Latch-driven exact counts, fake clock and ticker |

---

## 7. Key Takeaways

1. Logging must never break the business code, and must never fail silently: isolate, then count.
2. Bound every queue and name what is lost. Assert the real rule `offered == delivered + failed + queued + abandoned + workerDead + dropped`; `accepted` is just `offered - dropped`, so checking it proves nothing.
3. Make config immutable and swap it atomically. Cache derived data inside the snapshot.
4. A disabled log call should cost a compare.
5. ThreadLocal context does not cross threads. Capture on submit, restore after the task.

[← Topic 17: LRU / LFU Cache](../17-lru-lfu-cache/) · [Index](../README.md) · [Topic 19: Turn-Based Games](../19-turn-based-game-engine/)

**Self-grade: STRONG.** Remaining gap: no file rotation or shutdown hook, no back-off for a sink that keeps failing, and `close()` has no timeout of its own.
