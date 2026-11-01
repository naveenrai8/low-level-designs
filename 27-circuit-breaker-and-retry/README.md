# Topic 27: Circuit Breaker and Retry

A payment service calls a card processor. The processor starts timing out. Every request now waits 30 seconds, threads pile up, the whole service stalls, and when the processor comes back, a flood of retries knocks it over again. A *circuit breaker* stops calling a dependency that is clearly broken. A *retry* with a budget fixes small, short failures without making big ones worse. A *bulkhead* makes sure one slow dependency cannot use up every thread.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [45-Minute Timeline](#5-45-minute-timeline)
6. [Senior vs Staff Rubric](#6-senior-vs-staff-rubric)
7. [Key Takeaways](#7-key-takeaways)

---

## 1. The Problem

### What is really being probed

- Can you draw the three states and say exactly what moves between them?
- Do you notice that "let in N probes" is a race, that a late answer from an old call can lie to a new state, and that a probe can simply never come back?
- Do you know that retries multiply load, and what stops them from doing so?

### v1 and follow-ups

- **v1:** a circuit breaker with CLOSED, OPEN and HALF_OPEN states, a sliding window of recent outcomes, and a clock that tests move by hand.
- **F1:** retry with a budget and jitter.
- **F2:** a bulkhead: a cap on concurrent calls per dependency.
- **F3:** per-endpoint config and metrics.

### Clarifying questions, with the assumed answers

| Question | Assumed answer |
|---|---|
| Count-based or time-based window? | Both, behind one small interface. |
| What counts as a failure? | Any exception, unless a predicate says "not the dependency's fault". |
| Per process or shared? | Per process. The shared view is in the microservices guide. |
| Do we sleep in the code? | The logic never sleeps. Only `Sleeper.system()`, the production seam, calls `Thread.sleep`, like `Ticker.system()` calls `nanoTime`. Tests use a recorder. |

### Out of scope

Timeouts on the call itself (the caller owns them), fallbacks, shared state across instances, and an HTTP adapter.

---

## 2. How It Solves It

### Entities and invariants

- `CircuitBreaker` has a state, a generation number, a window and a probe gate. `CallPermit` is the ticket for one admitted call.
- Invariants: probes in flight are at most N; failures are at most total, and total is at most the window size; a permit reports one outcome only; the generation only goes up; a result lands only in the generation it was issued in.

### The interface and its error contract

```java
public <T> T call(ThrowingSupplier<T> call) throws Exception;   // CircuitOpenException if refused (call not run); the call's own exception is rethrown unchanged
public CallPermit acquire();                                   // for async code: then report permit.success() / failure() / ignore() exactly once
public CircuitState state();  public BreakerMetrics metrics();
// Retry.call(...)   : retries only what the predicate allows; never CircuitOpenException, BulkheadFullException or InterruptedException
// Bulkhead.call(...): BulkheadFullException if no slot; the call is not run
```

### The three states

```
          failure rate >= threshold
   CLOSED ---------------------------> OPEN
     ^                                  |
     | N probes succeed                 | wait has passed
     |                                  v
     +-------------------------------- HALF_OPEN --- one probe fails, or maxWaitInHalfOpen passes ---> OPEN
```

The allowed moves are a table in `CircuitState` (the idea from [Topic 06](../06-state-machines/)). `transition` throws on anything else, such as CLOSED straight to HALF_OPEN. The breaker reads a `Ticker` (nanoseconds, only moves forward) and tests move a `FakeTicker` by hand ([Topic 10](../10-time-and-testability/)). There is no timer thread: the clock is checked when the next call or state read arrives.

### A worked example (window of the last 2 calls, trip at 50 percent, at least 2 calls, wait 30 s, 2 probes)

| Time | What happens | Result |
|---|---|---|
| 0 s | slow call A is admitted (generation 0) | A is running |
| 1 s | call 1 fails | 1 call in the window, below the minimum of 2, no judging yet |
| 2 s | call 2 fails | 2 calls, 100 percent: **OPEN** (generation 1) |
| 3 s | a caller arrives | rejected at once, the dependency is not called |
| 31.999 s | a caller arrives | still OPEN (the wait is 30 s from 2 s) |
| 32 s | a caller arrives | wait has passed: **HALF_OPEN** (generation 2), this caller is probe 1 |
| 32 s | a second, then a third caller arrives | probe 2, then rejected: both slots are taken |
| 33 s | A finally returns "success" | its generation (0) is not 2, so it is dropped and counted in `staleResults` |
| 34 s | both probes succeed | **CLOSED**, with an empty window |

If a probe fails, the breaker goes straight back to OPEN and the 30 s wait starts again. If a probe never reports at all (cancelled, hung), HALF_OPEN would wait forever, so it also has `maxWaitInHalfOpen` (default 60 s): when it passes, the breaker goes back to OPEN, and the lost probe's late report is stale.

### The sliding window

*Sliding window* means "only recent outcomes count".

| Time | What happens | Window of the last 10 s (time-based) |
|---|---|---|
| 0 s | failure recorded | 1 call, 1 failure |
| 9.999 s | success recorded | 2 calls, 1 failure = 50 percent |
| 10 s | success recorded | the failure is exactly 10 s old, so it has left: 2 calls, 0 failures |

- `CountWindow` keeps the last N outcomes in a ring: O(1) time, O(N) memory.
- `TimeWindow` keeps outcomes younger than T: amortised O(1) time, memory proportional to the calls in the window. At 10k calls/s over 60 s that is about 600k entries per breaker, so production uses a ring of one-second buckets (what Resilience4j does).
- The boundary is: age below T is inside, age at T or more is out, tested to the nanosecond. The rule is `failures * 100 >= percent * total` in whole numbers, applied only when `total >= minimumCalls`. See [Topic 21](../21-rate-limiter/) for sliding windows in a limiter.

### Half-open lets in exactly N probes

The tempting code is `if (used < capacity) { used++; return true; }`. Two threads can both see the last slot free and both take it. `ProbeGate` reads the count and writes `count + 1` only if nobody changed it in between (*compare-and-set*, retrying on failure):

```java
int seen = used.get();
if (seen >= capacity) return false;
if (used.compareAndSet(seen, seen + 1)) return true;   // else loop and read again
```

The tests force the race with a barrier between the check and the act: the broken `RacyProbeGate` gives one slot to two threads, the real gate gives it to exactly one. Another test starts 24 threads behind a latch against 3 slots: exactly 3 reach the dependency.

Be honest about what guarantees N inside the breaker: `acquire()` is `synchronized`, so the lock already serialises the take. The CAS bounds the count even without the lock (removing `synchronized` from `acquire` alone does not fail the 24-thread test), and `ProbeGate` is where the race is shown in isolation. `release()` on a slot that was never taken throws instead of clamping, so a double release is loud.

### A late answer must not lie

The worked example above shows it: without the generation check, A's old success would count as a probe success. The same holds for a late failure (it must not re-open a healthy breaker) and a late "no verdict" (it must not free a slot of the new period). `CallPermit` carries the generation and `report` compares it under the lock.

### Concurrency model

- Shared state: state, generation, window, open and half-open times, probe count, counters.
- Primitive: one `synchronized` per breaker, held for a few field updates and never while the dependency runs. Locks and atomics are in the [Java concurrency guide](../../internal-pocs/Java-poc/concurrency/).
- A call that throws an `Error`, a predicate that throws, or an `InterruptedException` all end with the permit reported, so a slot is never leaked. An interrupt (the client gave up) and a bulkhead rejection (we are busy) count for nothing.

### Retry (F1)

A call that fails three times, base 100 ms, cap 1000 ms:

| Attempt | Fails? | Backoff before jitter | Wait (half fixed, half random) |
|---|---|---|---|
| 1 | yes | 100 ms | 50 to 100 ms |
| 2 | yes | 200 ms | 100 to 200 ms |
| 3 | yes | 400 ms | 200 to 400 ms |
| 4 | works | | return the value |

*Jitter* is random spread so that clients that failed together do not retry together. I chose "equal jitter" as [Topic 23](../23-delayed-job-scheduler/) does: the AWS post finds Full and Decorrelated jitter finish the total work slightly faster, but equal jitter never waits near zero, which protects a dependency that is still recovering. The cost is somewhat less spread. The random source is seeded, so tests assert the exact delay list against an independent calculation.

**The budget.** Think of a jar. Every new request drops 0.1 token in, every retry takes 1 whole token out, and the jar holds at most `maxTokens`. Kept in thousandths, so it is exact. Always-failing traffic from an empty jar gets at most 10 percent of requests plus `maxTokens` retries: the test sends 100 failing requests with 5 attempts allowed and sees exactly 110 dependency calls, not 500. There is no time decay: tokens earned while the breaker is OPEN carry over (up to the cap), which is why the cap exists. The SRE book's rule is retries over *all* requests under 10 percent; this budget is retries over *new* requests, so 10 percent here is about 9.1 percent of all attempts.

**Which errors, and composition.** Only exceptions the predicate accepts are retried, and `CircuitOpenException`, `BulkheadFullException` and `InterruptedException` never are, even with a catch-all predicate (the tests use `t -> true` so deleting that guard fails). Retry goes outside and the breaker inside, so each attempt asks the breaker: an OPEN rejection ends the loop at once, and a breaker that trips mid-loop makes a retry of 10 touch the dependency only twice. Retrying an OPEN rejection would just wait and ask again, which is the load the breaker removes. Waits go through `Sleeper`; if one is interrupted the flag is restored.

### Bulkhead (F2) and where it sits

```
caller -> Retry -> CircuitBreaker -> Bulkhead -> dependency
           ^           |                |
           |  CircuitOpenException      BulkheadFullException
           +-- neither is retried; neither counts as a breaker failure
```

The bulkhead is innermost, next to the dependency, so only calls that actually reach it use its slots, and a rejection is cheap. A bulkhead is a `Semaphore.tryAcquire`: no slot means reject at once and never run; the slot is released in a `finally`, so even an `Error` gives it back (see the [semaphore guide](../../internal-pocs/Java-poc/concurrency/24-semaphore/)). The test starts 12 threads behind a latch with a cap of 3. The admitted calls meet at a barrier inside the call (so they truly ran together) and hold their slots until all 9 others are rejected; the peak is exactly 3.

### F3: per-endpoint config and metrics

`BreakerRegistry` is built with an immutable map of per-endpoint `Endpoint(config, countsAsFailure)` settings, so there is no moment where a breaker is created before its override is in place. A gateway can say "an HTTP 4xx is not a failure" per route. `get` is thread-safe (32 threads, one instance), and `allMetrics()` returns a sorted map of `BreakerMetrics` (state, successes, failures, rejected, stale results, transitions).

### Code and tests (54 tests)

| File | What it holds |
|---|---|
| `CircuitBreaker`, `CircuitState`, `CallPermit`, `CircuitBreakerConfig` | the state machine, generations, permits, settings (the public API Topic 46 reuses) |
| `SlidingWindow`, `CountWindow`, `TimeWindow`, `ProbeGate` | package-private internals |
| `Retry`, `RetryBudget`, `Sleeper`, `Ticker` | F1 and the time seams |
| `Bulkhead`, `BreakerRegistry`, `BreakerMetrics` | F2 and F3 |
| `CircuitBreakerTest` (17) | thresholds, minimum calls, fast rejection, exact wait and window boundaries, illegal transitions, stale results |
| `CircuitBreakerHardeningTest` (7) | lost probes, throwing predicate, interrupts, slot and counter reset per period, stale "no verdict" |
| `HalfOpenConcurrencyTest` (4) | bug gate, fixed gate, release without acquire, 24 threads against 3 slots |
| `RetryTest` (15) | exact delays, exact budget count, filter, composition with a catch-all predicate, interrupts |
| `BulkheadTest` (4), `BreakerRegistryTest` (5), `WindowDifferentialTest` (2) | cap and release, per-endpoint settings and a concurrent get, both windows against a naive list model |

---

## 3. The Gaps

### The follow-up ladder

| Step | Design delta |
|---|---|
| F1 retry | Add a budget (a fraction of new requests), jitter, a retryable filter and a `Sleeper` seam. Never retry a breaker or bulkhead rejection. |
| F2 bulkhead | A semaphore per dependency, innermost. Rejection is fast and not a breaker failure. |
| F3 per-endpoint | An immutable map of settings built at start-up, a per-endpoint failure predicate, and a metrics map. |

### Misconceptions

| I thought | Actually |
|---|---|
| A breaker needs a timer thread to leave OPEN | No. It checks the clock on the next call. |
| Retry always makes things safer | Under a real outage, 3 attempts means 3 times the load. The budget caps it. |
| Retry an OPEN rejection later | The rejection is the breaker doing its job. Retrying at once is the load it blocks. |
| The failure rate is meaningful from call one | 1 failure of 1 call is 100 percent. That is why `minimumCalls` exists. |
| HALF_OPEN always ends | Not if a probe never reports. `maxWaitInHalfOpen` is the way out. |
| A bulkhead is a rate limiter | A rate limiter caps calls per second, a bulkhead caps calls in flight. A slow dependency fills a bulkhead while the rate stays low. |

### Trade-offs and when not to use

- A tight threshold trips on noise, a loose one trips late. Start from the dependency's normal error rate.
- A breaker turns a dependency's problem into `CircuitOpenException`, so decide the fallback first.
- Not worth it for calls that cannot overload anything. Retry only idempotent calls, or send an idempotency key ([Topic 11](../11-money-ids-idempotency/)).
- `Sleeper` is a blocking seam, not the delayed-job `Scheduler` of Topic 10, because retry here is a synchronous loop that holds its caller anyway.

### Production delta

- This HALF_OPEN is stricter than Resilience4j's, which compares the probe failure *rate* with the threshold; here one probe failure re-opens at once.
- Slow calls should count as failures (a slow-call threshold). Metrics should go to Micrometer or Prometheus. Retries should respect a caller deadline. None of these are coded.
- A ring of buckets for the time window (above). Shared state, mesh outlier detection and gateway placement: see the [microservices guide](../../internal-pocs/microservices-poc/25-circuit-breaker-bulkhead/). Topic 46 reuses `BreakerRegistry`, one breaker per route.

---

## 4. Official References

- [Resilience4j CircuitBreaker](https://resilience4j.readme.io/docs/circuitbreaker): states, sliding windows, minimum calls, permitted half-open calls, max wait in half-open.
- [AWS Builders' Library: Timeouts, retries and backoff with jitter](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/)
- [AWS Architecture Blog: Exponential Backoff And Jitter](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/): the jitter variants compared.
- [Martin Fowler: CircuitBreaker](https://martinfowler.com/bliki/CircuitBreaker.html)
- [Google SRE Book: Handling Overload](https://sre.google/sre-book/handling-overload/): per-request and per-client retry budgets.
- Book (not linkable): Nygard, *Release It!*, 2nd edition, Chapter 5 "Stability Patterns" (Circuit Breaker, Bulkheads).

---

## 5. 45-Minute Timeline

| Minutes | What to do |
|---|---|
| 0 to 5 | Ask the questions in section 1. Say why: stop a slow failure from spreading. |
| 5 to 12 | Draw the three states and the table. Define the window and the minimum calls. |
| 12 to 22 | Write the interface and `call`. Inject the ticker. Walk the worked example. |
| 22 to 30 | Half-open: probe slots, the check-then-act race, generations, and the lost probe. |
| 30 to 38 | Retry with backoff, jitter and a budget. Say why OPEN is not retried. |
| 38 to 45 | Bulkhead, per-endpoint config, metrics, then the production delta. |

## 6. Senior vs Staff Rubric

| | Senior | Staff |
|---|---|---|
| States | Draws the three states | Writes the table, rejects illegal moves, knows the exact boundary tick |
| Half-open | Allows a few calls | Proves exactly N, shows the race, and handles a probe that never reports |
| Late results | Not mentioned | Generation numbers so old calls cannot lie |
| Retry | Backoff | Budget, jitter, retryable filter, why OPEN and interrupts are not retried |
| Testing | Sleeps in tests | Injected ticker and sleeper, seeded jitter, mutation checks |
| Scope | One breaker | Bulkhead, per-endpoint config, metrics, and where it stops being local |

## 7. Key Takeaways

- A breaker has three states and a table of allowed moves. OPEN is cheap: reject at once. Leaving OPEN needs a clock check, not a thread.
- Judge the failure rate only after a minimum number of calls, and give HALF_OPEN a way out when a probe is lost.
- A late result needs a generation number. A retry budget turns "retries multiply load" into a fixed fraction, and an open breaker's rejection is never retried.

[← Topic 26: Connection Pool](../26-connection-pool/) · [Index](../README.md) · [Topic 28: ID Generator →](../28-id-generator/)

**Self-grade: STRONG.** Measured on one machine (JDK 21.0.8, 54 tests, green three runs in a row). Remaining gap: no slow-call threshold, no metrics export, and the shared-across-instances view is only linked, not built.
