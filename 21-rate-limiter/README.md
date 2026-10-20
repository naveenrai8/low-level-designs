# Topic 21: Rate Limiter

"Design a rate limiter" sounds like a 10-minute question: count requests, say no past N. The
interviewer is waiting for the three places where that answer breaks. Two callers race past the
check at the same instant. The "per minute" window lets 2× through at the boundary. And memory
grows with every key an attacker invents. A staff answer names all three before being asked,
codes one algorithm correctly under concurrency, and can say what changes on 50 instances behind
a load balancer.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [45-Minute Interview Timeline](#5-45-minute-interview-timeline)
6. [Senior vs Staff Rubric](#6-senior-vs-staff-rubric)
7. [Key Takeaways](#7-key-takeaways)

---

## 1. The Problem

### What breaks without one

One client (a buggy retry loop, a scraper, a credential-stuffing bot) sends 10,000 requests a
second. Your service has no notion of "too much from one caller", so that client takes every
thread and connection, and every other client times out. A rate limiter makes overload a
per-caller **429** instead of a global outage. It's the cheapest fairness and admission-control
tool there is.

### What the interviewer is really probing

| Signal | What they want to see |
|---|---|
| Concurrency correctness | Check-and-consume is **atomic per key**, with no global lock |
| Algorithm judgement | You know ≥ 2 algorithms, their memory and accuracy costs, and **when not to use** each |
| API design | The caller gets back *why*, *how many are left*, and *when to retry*, not a bare `boolean` |
| Testability | Time is injected. Tests are deterministic and don't call `sleep` |
| Resource bounds | Per-key state is evicted without changing any decision |
| Distributed seam | You know an in-process limiter enforces K × limit behind K instances, and what to do about it |

### v1 requirements (state these out loud)

1. `tryAcquire(key)` returns allow or deny **immediately**, without blocking.
2. Limit is `N permits per window W`, the same for every key (per-tier limits come in F1).
3. Keys are independent: alice exhausting her limit never affects bob.
4. A denied caller is told `retryAfter` and `remaining`.
5. Thread-safe under heavy contention on a single hot key.
6. Memory stays bounded as keys come and go.

### Clarifying questions (with the answers I assume if the interviewer shrugs)

| Question | Assumed answer | Why it matters |
|---|---|---|
| Is it in-process or shared across instances? | In-process for v1; distributed is F3 | Decides whether you code a class or a Redis script |
| Does it reject or queue (block until allowed)? | Reject | Queueing is traffic *shaping* (leaky bucket), a different component |
| Are bursts OK? | Yes, up to N | Picks token bucket over sliding log |
| What's the key: user, API key, IP, (user, endpoint)? | An opaque string the caller builds | Keeps the limiter generic |
| Can one request cost more than 1 (e.g. batch, tokens-per-LLM-call)? | Yes, `permits ≥ 1`, all-or-nothing | Changes the API and the deny math |
| Exact or approximate? | Approximate is fine at high limits; exact for login/OTP | Picks the algorithm |
| What happens if the limiter itself fails? | Fail **open** for general APIs, fail **closed** for security limits | A product decision, so ask it |

### Explicitly out of scope for v1

Request queueing/shaping, per-tier config, distributed enforcement, HTTP wiring (lives in
[`app/`](../app/)), and persistence across restarts.

---

## 2. How It Solves It

### Entities and invariants

| Type | Kind | Invariant |
|---|---|---|
| `RateLimit(permits, window)` | value (record) | `permits ≥ 1`, `window > 0`, `window ≥ permits ns` (so the token interval is ≥ 1ns) |
| `Decision(allowed, remaining, retryAfter)` | value (record) | `remaining ≥ 0`. An allowed decision has `retryAfter == 0`. A denied one has `retryAfter > 0` |
| `RateLimitAlgorithm<S>` | strategy (SPI) | Pure math over a state `S`. **Not** thread-safe, by design |
| `KeyedRateLimiter<S>` | the component | Per key: read time → check → consume runs atomically |
| `Ticker` | port | Monotonic nanoseconds. Production uses `System::nanoTime`; tests use a fake |

Design choice: each algorithm is **single-threaded math**, and `KeyedRateLimiter` owns *all*
the concurrency. That way the concurrency is written and tested once, and each algorithm can be
tested with plain sequential calls.

### Public interface first

```java
public interface RateLimiter {
    default Decision tryAcquire(String key) { return tryAcquire(key, 1); }

    /** All-or-nothing. Throws IllegalArgumentException if key is blank, permits < 1,
     *  or permits > limit (it could never succeed — a caller bug, not a 429). */
    Decision tryAcquire(String key, int permits);
}

public record Decision(boolean allowed, long remaining, Duration retryAfter) { ... }
```

`permits > limit` throws instead of denying. A deny says "retry later", and that request would
never succeed, so returning 429 would loop the client forever.

### Class diagram (whiteboard version)

```
            <<interface>>
             RateLimiter
     tryAcquire(key, permits): Decision
                  ^
                  | implements
     KeyedRateLimiter<S> ------------------> Ticker  (System::nanoTime | FakeTicker)
     - states: ConcurrentHashMap<String,S>
     + evictIdle(): int
                  |
                  | delegates math to (Strategy)
                  v
        <<interface>> RateLimitAlgorithm<S>
        newState(now) / tryAcquire(s, now, n) / isIdle(s, now)
           ^                 ^                     ^
     TokenBucket      SlidingWindowLog     SlidingWindowCounter
     S = {tokens,     S = ArrayDeque<Long>  S = {windowStart,
          lastRefill}     of grant times         current, previous}
```

### Sequence: one `tryAcquire`

```
caller            KeyedRateLimiter          CHM bin lock(key)          algorithm
  | tryAcquire(k,n)     |                          |                       |
  |-------------------->| validate(k, n)           |                       |
  |                     | states.compute(k, fn) -->| lock bin              |
  |                     |                          | now = ticker.read()   |  <- time read INSIDE lock
  |                     |                          | s = existing ?: newState(now)
  |                     |                          | d = tryAcquire(s, now, n) -->| refill/expire,
  |                     |                          |                       |  check, consume
  |                     |                          | return s (stays mapped)      |
  |                     |<-- unlock bin -----------|                       |
  |<-- Decision d ------|                          |                       |
```

### The algorithms

| Algorithm | State / key | Time / call | Accuracy | Behaviour | Use when | Don't use when |
|---|---|---|---|---|---|---|
| **Fixed window** (not coded; it's a trap) | 2 longs | O(1) | **2× burst at the boundary**: N at 0:59, N more at 1:00 | Resets on a tick | Coarse quotas ("1M calls/month") where the boundary doesn't matter | Protecting a backend from bursts |
| **Token bucket** ([`TokenBucket`](src/main/java/com/lld/ratelimiter/TokenBucket.java)) | 2 longs | O(1) | Exact *for its own model* | Burst up to N, then a steady N/W | Default for API limits. Stripe, AWS API Gateway and Guava are all token-bucket family | You need "never more than N in any W". It allows ~2N in a W (tested) |
| **Sliding-window log** ([`SlidingWindowLog`](src/main/java/com/lld/ratelimiter/SlidingWindowLog.java)) | O(N) timestamps | O(1) amortised; O(N) to compute a deny's wait | **Exact**: ≤ N in every window `(t−W, t]` | No bursts beyond N | Small N with a hard guarantee: login attempts, OTP sends, password resets | N in the thousands per key. Memory is ~24 B per grant, so 10k/min ≈ 240 KB per hot key (estimate) |
| **Sliding-window counter** ([`SlidingWindowCounter`](src/main/java/com/lld/ratelimiter/SlidingWindowCounter.java)) | 3 longs | O(1) | **Approximate**: assumes the previous window was uniform | Smooth | High-volume edge limits where O(1) memory matters (Cloudflare's choice) | Adversarial clients can cluster traffic. Worst case nears 2× (tested: 18 grants in one real second at limit 10) |
| **Leaky bucket (queue)** | a queue | O(1) | n/a | *Shapes* output: adds latency instead of rejecting | Smoothing egress to a fragile downstream | You need an immediate yes/no |
| **GCRA** | 1 long (TAT) | O(1) | Equivalent to token bucket | | Single-field state is ideal for Redis | Interviews, unless asked. It's harder to explain in 45 min |

**Token bucket refill, the subtle part** (`TokenBucket.refill`):

```java
long earned = elapsed / nanosPerToken;           // whole tokens only; integer math, no drift
if (earned >= capacity - tokens) {
    tokens = capacity; lastRefill = now;          // full: idle time beyond "full" is NOT banked
} else {
    tokens += earned;
    lastRefill += earned * nanosPerToken;         // keep partial progress toward the next token
}                                                 // (setting lastRefill = now leaks rate; tested)
```

No background thread ever refills a bucket. Refill is computed lazily on access, so 1M keys cost
zero CPU while idle.

### Concurrency model

- **Shared state:** one mutable state object per key, inside a `ConcurrentHashMap<String, S>`.
- **Invariant:** for one key, `read time → check → consume` is atomic, so N racing callers get
  at most `limit` grants.
- **Primitive:** `ConcurrentHashMap.compute(key, fn)`. The CHM Javadoc guarantees the whole
  function runs atomically for that key. Callers on one key are serialised, and callers on
  different keys run in parallel unless they share a hash bin. There's no global lock.
- **Why not `synchronized` on the limiter?** It's correct, but it serialises *all* keys. One
  hot key's traffic would then queue everyone.
- **Why not `AtomicLong` + CAS?** It works for one counter, but token bucket updates two fields
  (`tokens`, `lastRefill`) together, so you'd need an immutable state object and a CAS loop on
  an `AtomicReference`. That's valid and lock-free, but there's more to get wrong in 45 minutes.
- **Why time is read *inside* the lock:** read it before `compute` and thread A (t=100) can
  enter after thread B (t=105). Per-key time then goes backwards, and `elapsed` turns negative.
- **Why `System.nanoTime`, not `currentTimeMillis`:** wall clocks jump (NTP, leap smear, a
  manual change), while nanoTime is monotonic within a JVM. That's also why `Ticker` exists:
  tests inject a `FakeTicker` and never sleep.
- **Lambda rules:** keep it short, and never touch *another* key of the same map inside it. That
  can deadlock or throw `IllegalStateException: Recursive update`.

**How much does the atomicity matter?** A check-then-act version (`get` → compare → `put`, each
step atomic on its own) was run against this module's `ConcurrencyTest`. 19 of 20 runs
over-granted, from 1,004 up to all 6,400 attempts against a limit of 1,000. Lost updates keep the
counter low, so the over-grant has no ceiling (measured on an Apple-silicon laptop, 32 threads).

### Eviction: bounded memory without changing any decision

`isIdle(state, now)` is defined as "this state behaves exactly like a fresh one", e.g. a token
bucket that would be full by now. `evictIdle()` removes such keys with `computeIfPresent`
(return `null` → removed), holding the **same bin lock** as `tryAcquire`. So it can't race an
in-flight acquire. `RateLimiterContractTest.evictionIsLossless` runs 2,000 random steps with and
without eviction after every call, and asserts identical decisions.

The sliding-window counter needed a fix to make this true. Re-align a key's window grid whenever
both counters are zero, otherwise a kept key and a fresh key disagree about where windows start.

### Code and tests

| File | What to look at |
|---|---|
| [`KeyedRateLimiter`](src/main/java/com/lld/ratelimiter/KeyedRateLimiter.java) | `compute` + holder array; `evictIdle` |
| [`TokenBucket`](src/main/java/com/lld/ratelimiter/TokenBucket.java) | integer refill with carry-over; exact `retryAfter` |
| [`SlidingWindowLog`](src/main/java/com/lld/ratelimiter/SlidingWindowLog.java) | wait = the *k*-th oldest grant + W − now |
| [`SlidingWindowCounter`](src/main/java/com/lld/ratelimiter/SlidingWindowCounter.java) | solving the estimate formula for `retryAfter` |
| [`RateLimiterContractTest`](src/test/java/com/lld/ratelimiter/RateLimiterContractTest.java) | one contract, run against all 3 via `@EnumSource` |
| [`AlgorithmBehaviourTest`](src/test/java/com/lld/ratelimiter/AlgorithmBehaviourTest.java) | what makes each algorithm different, including the two "~2×" tests |
| [`ConcurrencyTest`](src/test/java/com/lld/ratelimiter/ConcurrencyTest.java) | 32 threads, start gate, frozen time, so exactly `limit` grants |
| [`app/…/ratelimiter`](../app/src/main/java/com/lld/app/ratelimiter/) | HTTP adapter: 429 + `Retry-After` |

Run it: see [`commands.md`](commands.md).

---

## 3. The Gaps

### Follow-up rounds (this is where staff is decided)

**F1: "Free users get 10/min, paid get 1000/min, and ops wants to change limits without a
deploy."**

- Delta: add a `LimitPolicy` that resolves `key → RateLimit` (e.g. by tier) and hold **one
  `KeyedRateLimiter` per distinct limit** in an immutable `Map<RateLimit, KeyedRateLimiter>`.
  Publish it through a `volatile` field or an `AtomicReference`. A reload builds a new map and
  swaps it in one write, so readers see the whole old config or the whole new one, never a mix.
- The trap: what happens to in-flight state on reload? Dropping it resets everyone's limit, so
  a reload turns into a free burst. Migrate it instead: keep limiters whose `RateLimit` didn't
  change, and for changed ones accept the reset, or clamp tokens to `min(tokens, newCapacity)`.
  Say which one you chose.
- Staff extension: **compound limits** ("10/s *and* 1000/day"). Both have to pass, or neither
  consumes. Two limiters means two locks, so the options are:
  - check-then-consume across both. That's the race again.
  - consume A, then on B's deny **refund** A. That needs a `release(key, n)` on the SPI.
  - store both limits in **one state object** under one key lock. Simplest and atomic; pick this.
  - This is the multi-object atomicity problem from
    [Topic 08](../08-multi-object-atomicity/).

**F2: "We have 50M distinct keys a day. Some attacker sends random API keys."**

- Memory estimate (back-of-envelope, not measured): a token-bucket state is ~32 B, plus a CHM
  node ~32 B, plus a short `String` key ~48 B ≈ **~110 B per key**. So 1M live keys ≈ 110 MB.
  Eviction keeps "live" ≈ keys active within ~one window, not keys seen today.
- The attack: every unknown key creates state. Mitigations, in order:
  1. **Limit before auth by a coarse key** (IP, /24 prefix) and only then by API key. Unknown
     keys can't create per-key state.
  2. **Don't create state for a request that leaves it fresh.** Token bucket at `permits=1`
     still creates it, so a cheaper trick is a small global limiter on *new-key creation*.
  3. **Bounded map with size-based eviction (LRU/Caffeine `maximumSize`) is lossy, and lossy
     here means *fail-open*.** Evicting a non-idle key resets its limit, so an attacker who
     churns the cache resets their *own* limit. Prefer time-based expiry: Caffeine
     `expireAfterAccess(W)` is lossless for token bucket and sliding log, and `2W` for the
     counter. Reason: an idle-for-W bucket is full by definition.
  4. **Count-min sketch** for fixed memory. It over-counts, so it fails *closed* on innocent
     keys that collide. That's OK for abuse detection, not for paid quotas.
- `evictIdle()` is O(keys), so schedule it (the `app` does every 30s). Don't run it per request.

**F3: "It runs on 40 instances behind a load balancer."**

- The problem: each instance enforces its own copy, so a client spread round-robin gets up to
  **40 × limit**.
- Options:

  | Option | How | Cost | Failure mode |
  |---|---|---|---|
  | Divide | each instance enforces `limit / K` | none | wrong on autoscale, uneven LB, small limits (10/40 = 0) |
  | Sticky routing | consistent-hash key → instance ([Topic 29](../29-consistent-hashing/)) | LB config | rebalancing resets limits; hot key = hot instance |
  | **Central store** | Redis + Lua script: refill + check + consume in one atomic `EVAL` | +1 network RTT per request (sub-ms in-region; measure yours) | Redis down → you must choose fail-open (with a local fallback limiter) or fail-closed |
  | Local + async sync | decide locally, gossip/flush counts every ~100ms | small | over-admits by up to (sync interval × rate × K) |

- Redis details a staff candidate mentions:
  - Use **Lua, not GET-then-SET**. The latter is the same check-then-act race, just over a
    network.
  - Read time **inside the script** (`redis.call('TIME')`) so clients with skewed clocks agree.
  - Set a key TTL ≈ W so Redis evicts idle keys, which is the F2 answer for free.
  - Watch for a single hot key pinned to one Redis Cluster shard.
  - Give the limiter a **tight timeout** (a few ms). A slow limiter is worse than no limiter.
- See [Redis-poc rate limiting](../../pocs/Redis-poc/11-patterns/02-rate-limiting/) and
  [its capstone gateway](../../pocs/Redis-poc/15-capstones/01-rate-limited-api-gateway/).
  System-level framing (admission control, load shedding) is in
  [SystemDesign-poc 64](../../pocs/SystemDesign-poc/64-rate-limiting-and-admission-control/).

### Common mistakes

| Mistake | Why it's wrong | Seen in |
|---|---|---|
| `if (count.get() < N) count.incrementAndGet()` | Check-then-act. Two threads both pass the check | most first drafts |
| A `HashMap` + `synchronized` per method, or one global lock | Correct but serialises every key | "safe" drafts |
| Reading the clock before taking the lock | Per-key time goes backwards, so a negative `elapsed` | subtle; rarely caught |
| `System.currentTimeMillis()` | NTP jumps make refill negative or huge | |
| `lastRefill = now` on every refill | Throws away partial progress, so the real rate is lower than configured | `partialProgressTowardTheNextTokenIsKept` |
| A background thread refilling every bucket | O(keys) CPU while idle; doesn't scale | |
| Calling fixed window "sliding" | 2× at the boundary | `hasNoBoundaryBurst` shows the log doesn't |
| Thinking token bucket caps requests per window | Allows ~2N in a window: N burst + N refill | `isNotAPerWindowCap…` |
| Returning `boolean` | The caller can't set `Retry-After`; clients retry-storm | |
| 429 for `permits > limit` | Never succeeds, so the client retries forever | `rejectsRequestsThatCanNeverSucceed` |
| LRU eviction of limiter state | Evicting a live key resets its limit (fail-open) | |
| `Retry-After` in milliseconds | HTTP `Retry-After` is **seconds** (round *up*) | `app/` adapter |
| `Thread.sleep` in tests | Slow and flaky; inject the clock | `FakeTicker` |

### When NOT to use a rate limiter

- **Protecting capacity against *aggregate* overload:** per-key limits don't stop 1M
  well-behaved clients from collectively exceeding capacity. You need concurrency limits or
  adaptive load shedding (see microservices-poc
  [27](../../pocs/microservices-poc/27-rate-limiting-load-shedding/)).
- **Cost/latency of downstream calls varies 100×:** count *cost*, not requests (`permits = cost`),
  or limit concurrency instead.
- **Billing quotas:** these need durable, auditable counters, not an in-memory approximation.

---

## 4. Official References

- `ConcurrentHashMap.compute`: atomicity guarantee and "must not attempt to update any other
  mappings":
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html#compute(K,java.util.function.BiFunction)
- `System.nanoTime`: monotonic and only meaningful as a difference:
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/System.html#nanoTime()
- RFC 6585 §4, `429 Too Many Requests`: https://www.rfc-editor.org/rfc/rfc6585#section-4
- RFC 9110 §10.2.3, `Retry-After` (delay-seconds): https://www.rfc-editor.org/rfc/rfc9110#field.retry-after
- IETF draft, `RateLimit` header fields: https://datatracker.ietf.org/doc/draft-ietf-httpapi-ratelimit-headers/
- Stripe, *Scaling your API with rate limiters*: https://stripe.com/blog/rate-limiters
- Cloudflare, *How we built rate limiting capable of scaling to millions of domains* (sliding
  window counter, 0.003% error on real traffic): https://blog.cloudflare.com/counting-things-a-lot-of-different-things/
- Guava `RateLimiter` (a blocking, smooth token bucket): https://guava.dev/releases/snapshot-jre/api/docs/com/google/common/util/concurrent/RateLimiter.html
- Redis rate-limiting patterns: https://redis.io/learn/howtos/ratelimiting
- Envoy global rate limiting (the "central service" option at scale): https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/other_features/global_rate_limiting
- Brandur, *Rate limiting, cells, and GCRA*: https://brandur.org/rate-limiting

---

## 5. 45-Minute Interview Timeline

| Min | Do | Say out loud |
|---|---|---|
| 0–5 | Clarifying questions (table above); write v1 requirements | "In-process first, distributed as a follow-up?" |
| 5–10 | `RateLimiter` interface + `Decision`; pick token bucket, name the alternatives in one line each | "Boolean isn't enough. The caller needs `Retry-After`." |
| 10–25 | Code `TokenBucket` math, then `KeyedRateLimiter` with `compute` | "Time is read inside the lock, and here's why." |
| 25–33 | Tests: burst = N, refill, per-key isolation, concurrency (latch + frozen clock) | "This test fails against a check-then-act version." |
| 33–45 | Follow-ups F1 → F3; eviction if not already done | "In-process means K × limit. Here are 4 options." |

If you're behind at minute 25, skip sliding-window code entirely. Name it, give its costs, and
move on. Correct concurrency beats a second algorithm.

## 6. Senior vs Staff Rubric

| Area | Senior (hire) | Staff (strong hire) |
|---|---|---|
| Algorithm | Codes token bucket correctly | Also says when it's wrong (~2N/window) and picks the log for login limits |
| Concurrency | Uses a concurrent map + per-key lock | Explains *why* atomic per key, why time goes inside the lock, and why not a global lock or CAS |
| API | Returns allow/deny | `Decision` with `remaining`/`retryAfter`; `permits > limit` is a programming error, not a 429 |
| Tests | Happy-path unit tests | Fake clock, a concurrency test that would catch the race, a property test for eviction |
| Memory | "Use a TTL cache" | Shows LRU eviction fails open; proposes lossless idle eviction |
| Distributed | "Use Redis" | Lua for atomicity, server-side time, fail-open vs fail-closed decided per limit, timeout budget |
| Drives | Waits for follow-ups | Raises F2/F3 before being asked; cuts scope deliberately when time is short |

## 7. Key Takeaways

1. The core of the problem is **atomic read-time → check → consume per key**.
   `ConcurrentHashMap.compute` gives it without a global lock.
2. Keep the algorithm as single-threaded math, and keep all concurrency in one wrapper. Test each
   separately.
3. **Token bucket** is the default, and it allows bursts: ~2N in any window. **Sliding log**
   is exact but O(N) memory. **Sliding counter** is O(1) but approximate, up to ~2× worst case.
   **Fixed window** has the 2× boundary burst.
4. Inject a monotonic clock. Tests advance time instead of sleeping.
5. Return a `Decision` (remaining, retryAfter), and throw for requests that can never succeed.
6. Memory: evict only **idle** keys (state equals a fresh one). Size-based eviction resets live
   limits and fails open.
7. In-process limiters enforce **K × limit** behind K instances. The fix (Redis + Lua) adds a
   network hop and a fail-open-or-closed decision.
8. Lazy refill on access, never a background thread per key.

---

**Self-grade: STRONG.** It has the full spine, all three algorithms coded and tested, measured
numbers where measured (the check-then-act over-grant), estimates labelled as estimates (memory
per key), explicit failure modes and when-not-to. The known gap: the Redis/Lua version is
described, not coded. That lives in Redis-poc.

[← Topic 20: Library Management](../20-library-management/) · [Index](../README.md) · [Topic 22: Bounded Blocking Queue & Thread Pool →](../22-blocking-queue-thread-pool/)
